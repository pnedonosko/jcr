package org.exoplatform.services.jcr.impl.storage.value.s3;

import com.amazonaws.event.ProgressEvent;
import com.amazonaws.event.ProgressEventType;
import com.amazonaws.event.ProgressListener;
import com.amazonaws.services.s3.model.S3Object;

import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * S3 values manager and related resources cleaner. This process manage locking of a value object in S3 during its creation,
 * modification or removal. Lock should be placed on a object request creation and should be added as listener to this 
 * request to unlock on the request completion or failure. Another goal of this process it's cleanup of resources obtained in
 * object requests.<br>    
 * 
 * Created by The eXo Platform SAS.
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: Listener.java 00000 Oct 14, 2014 pnedonosko $
 * 
 */
class S3Process implements ProgressListener
{
   private static final Log LOG = ExoLogger.getLogger("exo.jcr.component.core.s3.S3ValueProgressListener");

   private static final Object dummy = new Object();

   /**
    * Process modes.
    */
   enum Mode {
      /**
       * Initial mode. Can be upgraded to any other mode. 
       * Will be used if process will not be locked by one of lock methods. 
       * In this mode process will complete on S3 request success or failure and 
       * cleanup will be done on transmit completion/failure/cancellation.
       */
      NONE,

      /**
       * Read mode enabled by {@link S3Process#lockRead(false)}. Can be upgraded to WRITE. Cannot be upgraded to METADATA.
       * In this mode process will complete on S3 request success or failure and 
       * cleanup will be done on transmit completion/failure/cancellation.
       */
      READ,

      /**
       * Write mode enabled by {@link S3Process#lockWrite()}. Can be upgraded to READ. Cannot be upgraded to METADATA.
       * In this mode process will complete on S3 request success or failure and 
       * cleanup will be done on transmit completion/failure/cancellation.
       */
      WRITE,

      /**
       * Reading of metadata mode enabled by {@link S3Process#lockRead(true)}. Can be upgraded to WRITE or READ.
       * In this mode process will complete and cleanup on S3 request success or failure.
       */
      METADATA
   };

   /**
    * Used to avoid creating the extra thread until absolutely necessary. Copied from S3 SDK. 
    */
   static final class Cleaner
   {
      /** A single thread pool for executing all ProgressListener callbacks. **/
      private static final ExecutorService executor = createNewExecutorService();

      /**
       * Creates a new single threaded executor service for performing the
       * callbacks.
       */
      private static ExecutorService createNewExecutorService()
      {
         return Executors.newSingleThreadExecutor(new ThreadFactory()
         {
            public Thread newThread(Runnable r)
            {
               Thread t = new Thread(r);
               t.setName("exo-s3process-cleanup-thread");
               t.setDaemon(true);
               return t;
            }
         });
      }
   }

   /**
    * Non-reentrant lock which allows only single writer or many readers at time. If writer meet readers it will wait for them
    * and then acquire exclusive lock. If reader meet a writer, it will wait for it and then place a shared lock. <br>
    * Any thread can release exclusive and shared locks - this way we let S3 SDK's async listeners to release an value lock 
    * by its ID (bucker + value ID) already bound to the process instance. It is fair lock (it respects order of lock acquisition).  
    */
   class Sync
   {
      /**
       * Current writer (exclusive lock holder).
       */
      private final AtomicReference<Thread> writer = new AtomicReference<Thread>();

      /**
       * Writer in process of lock acquisition: it waits for readers that were active when it held exclusive lock.
       */
      private final AtomicReference<Thread> waitingWriter = new AtomicReference<Thread>();

      /**
       * Counter of acquired shared locks (active readers).
       */
      private final AtomicLong readers = new AtomicLong(0);

      /**
       * Queue of readers waiting for current writer.
       */
      private final Set<Thread> readersQueue = new LinkedHashSet<Thread>();

      private final Lock readersLock = new ReentrantLock(true);

      /**
       * Queue of writers waiting for current writer.
       */
      private final Set<Thread> writersQueue = new LinkedHashSet<Thread>();

      private final Lock writersLock = new ReentrantLock(true);

      /**
       * Wait for current writer and set this thread as such. Wait for already existing shared queue (readers).
       */
      void lockExclusive()
      {
         final Thread current = Thread.currentThread();

         // acquire write ownership
         while (!writer.compareAndSet(null, current))
         {
            // writer already exists, wait in the queue
            writersLock.lock();
            try
            {
               writersQueue.add(current);
            }
            finally
            {
               writersLock.unlock();
            }
            LockSupport.park(this);
         }

         // current thread is an exclusive writer, wait for active reads now
         while (readers.get() > 0)
         {
            Thread previous = waitingWriter.getAndSet(current);
            if (previous != null)
            {
               // This should not happen, but if does we let the previous writer to go
               if (unpark(previous))
               {
                  LOG.warn("Released previous waiting writer " + previous);
               }
            }
            LockSupport.park(this);
         }
      }

      /**
       * Clean current writer and release others waiting in shared queue (of readers) and in exclusive queue (of writers).
       */
      void releaseExclusive()
      {
         writer.set(null);

         // readers first
         unpark(readersQueue, readersLock);

         // then writers 
         unpark(writersQueue, writersLock);
      }

      /**
       * Wait for current writer storing this thread in shared queue (of readers). Return immediately if no current writer.
       */
      void lockShared()
      {
         final Thread current = Thread.currentThread();

         // if writer exist
         while (writer.get() != null)
         {
            // put in the queue and wait writer will release the exclusive lock
            readersLock.lock();
            try
            {
               readersQueue.add(current);
            }
            finally
            {
               readersLock.unlock();
            }
            LockSupport.park(this);
         }

         // add one more active readers
         readers.incrementAndGet();
      }

      /**
       * Release waiting writer if have such.
       */
      void releaseShared()
      {
         // remove one active reader
         readers.decrementAndGet();

         // inform waiting writer if no more readers
         if (readers.get() == 0)
         {
            Thread writer = waitingWriter.getAndSet(null);
            if (writer != null)
            {
               unpark(writer);
            }
         }
      }

      private void unpark(Collection<Thread> queue, Lock lock)
      {
         // remove all available currently threads from the queue
         // and then unpark them - this way we let threads add itself to the queue again if required
         List<Thread> snapshot = new ArrayList<Thread>();
         lock.lock();
         try
         {
            for (Iterator<Thread> iter = queue.iterator(); iter.hasNext();)
            {
               snapshot.add(iter.next());
               iter.remove();
            }
         }
         finally
         {
            lock.unlock();
         }
         for (Thread th : snapshot)
         {
            unpark(th);
         }
      }

      private boolean unpark(Thread thread)
      {
         Object blocker;
         if ((blocker = LockSupport.getBlocker(thread)) == this)
         {
            LockSupport.unpark(thread);
            return true;
         }
         else
         {
            if (LOG.isDebugEnabled())
            {
               LOG.debug("!!!!! Thread not unparked as has different blocker object " + blocker);
            }
            return false;
         }
      }
   }

   private final String id;

   private final Map<S3Object, Object> resources = Collections.synchronizedMap(new WeakHashMap<S3Object, Object>());

   private final Sync sync = new Sync();

   private final AtomicBoolean active = new AtomicBoolean(true);

   private Mode mode = Mode.NONE;

   private ProgressListener parent;

   S3Process(String vid)
   {
      this.id = vid;

   }

   void setParent(final ProgressListener parent)
   {
      if (parent != null && parent != this && parent != this.parent)
      {
         synchronized (this)
         {
            if (this.parent == null)
            {
               this.parent = parent;
            }
            else if (this.parent instanceof S3Process)
            {
               // chain in my parent
               ((S3Process)this.parent).setParent(parent);
            }
            else if (parent instanceof S3Process)
            {
               // chain my parent in the given one
               ((S3Process)parent).setParent(this.parent);
            }
            else
            {
               LOG.info("Given parent not S3Process (" + parent.getClass().getName()
                  + ") and parent already set to instance of " + this.parent.getClass().getName());

               final ProgressListener myParent = this.parent;
               // chain both parents via composite
               this.parent = new ProgressListener()
               {
                  @Override
                  public void progressChanged(ProgressEvent progressEvent)
                  {
                     myParent.progressChanged(progressEvent);
                     parent.progressChanged(progressEvent);
                  }
               };
            }
         }
      }
   }

   /**
   * {@inheritDoc}
   */
   @Override
   public boolean equals(Object obj)
   {
      if (obj == this)
      {
         return true;
      }
      else if (this.getClass().isAssignableFrom(obj.getClass()))
      {
         S3Process other = (S3Process)obj;
         return getId().equals(other.getId());
      }
      return false;
   }

   void addResource(S3Object resource)
   {
      resources.put(resource, dummy);
   }

   String getId()
   {
      return id;
   }

   /**
    * {@inheritDoc}
     */
   @Override
   public void progressChanged(ProgressEvent progressEvent)
   {
      // this method can be called by several threads in general case
      // logic here should be safe for already completed state - thus don't rely on external state
      // just a cleanup if something related to this process is available
      if (active.get())
      {
         ProgressEventType type = progressEvent.getEventType();

         // get the chained parent at the beginning as complete() will clean it
         // TODO use atomic for proper concurrency

         // complete (1) --> cleanup (2)
         final ProgressListener parent = this.parent;

         if (type == ProgressEventType.CLIENT_REQUEST_SUCCESS_EVENT)
         {
            if (LOG.isDebugEnabled())
            {
               LOG.debug(">>>> " + type.name() + ": " + id + " " + progressEvent.getBytes() + " "
                  + progressEvent.getBytesTransferred());
            }
            complete();
            if (mode == Mode.METADATA)
            {
               cleanup();
            }
         }
         else if (type == ProgressEventType.TRANSFER_COMPLETED_EVENT)
         {
            if (LOG.isDebugEnabled())
            {
               LOG.debug("<<<< " + type.name() + ": " + id + " " + progressEvent.getBytes() + " "
                  + progressEvent.getBytesTransferred());
            }
            cleanup();
         }
         else if (type == ProgressEventType.CLIENT_REQUEST_FAILED_EVENT)
         {
            if (LOG.isDebugEnabled())
            {
               LOG.debug(">>>> " + type.name() + ": " + id);
            }
            // TODO retry the operation
            complete();
            if (mode == Mode.METADATA)
            {
               cleanup();
            }
         }
         else if (type == ProgressEventType.TRANSFER_FAILED_EVENT)
         {
            if (LOG.isDebugEnabled())
            {
               LOG.debug("<<<< " + type.name() + ": " + id);
            }
            cleanup();
         }
         else if (type == ProgressEventType.TRANSFER_CANCELED_EVENT)
         {
            // XXX should not be never called as CLIENT_REQUEST* above will precede it

            if (LOG.isDebugEnabled())
            {
               LOG.debug("<<<< !!!!! " + type.name() + ": " + id);
            }
            // TODO should we retry if one of the specified constraints
            // was not met (ex: matching ETag, modified since date, etc.)? 
            // Note that in case of getObject() it returns null for this kind of event,
            // but upload or copy throws AmazonClientException.
            cleanup();
         }
         else if (type == ProgressEventType.CLIENT_REQUEST_RETRY_EVENT)
         {
            if (LOG.isDebugEnabled())
            {
               LOG.debug(">>>> !!!!! " + type.name() + ": " + id);
            }
         }
         //         else if (type == ProgressEventType.RESPONSE_CONTENT_LENGTH_EVENT)
         //         {
         //            if (LOG.isDebugEnabled())
         //            {
         //               LOG.debug("#### " + type.name() + ": " + id + " " + progressEvent.getBytes() + " " + progressEvent.getBytesTransferred());
         //            }
         //         }
         //         else if (type == ProgressEventType.RESPONSE_BYTE_TRANSFER_EVENT)
         //         {
         //            if (LOG.isDebugEnabled())
         //            {
         //               LOG.debug("==== " + type.name() + ": " + id + " " + progressEvent.getBytes() + " " + progressEvent.getBytesTransferred());
         //            }
         //         }
         //         else if (type == ProgressEventType.RESPONSE_BYTE_DISCARD_EVENT)
         //         {
         //            if (LOG.isDebugEnabled())
         //            {
         //               LOG.debug("???? " + type.name() + ": " + id + " " + progressEvent.getBytes() + " " + progressEvent.getBytesTransferred());
         //            }
         //         }
         else
         {
            // TODO ?
         }

         if (parent != null)
         {
            if (LOG.isDebugEnabled())
            {
               LOG.debug(">>>>>> " + this + " fire event to parent " + parent);
            }
            parent.progressChanged(progressEvent);
         }
      }
   }

   void waitRead()
   {
      lockRead(false);
      unlockRead();
   }

   void lockRead(boolean asMetadata)
   {
      sync.lockShared();

      // set mode
      if (asMetadata && mode == Mode.NONE)
      {
         this.mode = Mode.METADATA;
      }
      else
      {
         this.mode = Mode.READ;
      }
   }

   void unlockRead()
   {
      sync.releaseShared();
   }

   void lockWrite()
   {
      sync.lockExclusive();

      // set/upgrade mode
      this.mode = Mode.WRITE;
   }

   void unlockWrite()
   {
      sync.releaseExclusive();
   }

   private void complete()
   {
      // remove from S3 SDK level
      S3ValueUtil.removeProcess(this);

      // unlock own locks
      unlockRead();
      unlockWrite();
   }

   private void cleanup()
   {
      // disable S3 listener
      active.set(false);

      // release resources a bit later to let them be used
      if (resources.size() > 0)
      {
         Cleaner.executor.submit(new Runnable()
         {
            @Override
            public void run()
            {
               Thread.yield();
               try
               {
                  Thread.sleep(1000 * 15); // XXX 15sec
                  for (S3Object res : resources.keySet())
                  {
                     try
                     {
                        res.close();
                        Thread.yield();
                     }
                     catch (Throwable e)
                     {
                        LOG.warn("Error closing S3 object", e);
                     }
                  }
               }
               catch (InterruptedException e)
               {
                  LOG.warn("S3 object cleaner interrupted: " + e);
               }
               finally
               {
                  resources.clear();
               }
            }
         });
      }
      // clean the chain
      parent = null;
   }

}
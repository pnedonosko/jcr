/*
 * Copyright (C) 2014 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.exoplatform.services.jcr.impl.storage.value.s3;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.AmazonWebServiceRequest;
import com.amazonaws.event.ProgressListener;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CopyObjectRequest;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.DeleteObjectsResult;
import com.amazonaws.services.s3.model.GetObjectMetadataRequest;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.MultiObjectDeleteException;
import com.amazonaws.services.s3.model.MultiObjectDeleteException.DeleteError;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectSummary;

import org.exoplatform.services.jcr.datamodel.ValueData;
import org.exoplatform.services.jcr.impl.dataflow.SpoolConfig;
import org.exoplatform.services.jcr.impl.dataflow.ValueDataUtil;
import org.exoplatform.services.jcr.impl.dataflow.persistent.CleanableFilePersistedValueData;
import org.exoplatform.services.jcr.impl.storage.value.fs.operations.ValueFileIOHelper;
import org.exoplatform.services.jcr.impl.util.io.SwapFile;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An utility class that will propose the most common operations on Amazon S3
 * 
 * @author <a href="mailto:nfilotto@exoplatform.com">Nicolas Filotto</a>
 * @version $Id$
 *
 */
public class S3ValueUtil
{
   private static final Log LOG = ExoLogger.getLogger("exo.jcr.component.core.s3.S3ValueUtil");

   private static final AtomicLong SEQUENCE = new AtomicLong();

   /**
    * Current listeners of writes in progress.
    */
   private static final ConcurrentHashMap<String, S3Process> processes = new ConcurrentHashMap<String, S3Process>();

   private S3ValueUtil()
   {
   }

   /**
    * Deletes a given key for a given bucket using the provided AS3
    * @param as3 the AS3 to use for the deletion
    * @param bucket the bucket in which we do the deletion
    * @param key the key to delete
    * @return <code>true</code> if it could be deleted, <code>false</code> otherwise
    */
   public static boolean delete(AmazonS3 as3, String bucket, String key)
   {
      if (LOG.isDebugEnabled())
      {
         LOG.debug("delete >>> " + bucket + " " + key);
      }

      DeleteObjectsRequest request = new DeleteObjectsRequest(bucket);
      request.withKeys(key);

      // respect the order of modifications, as the deleting value still can be in
      // process of modification which depends on its size and network 
      writeLock(bucket, key, request);

      try
      {
         DeleteObjectsResult result = as3.deleteObjects(request);
         return !result.getDeletedObjects().isEmpty();
      }
      catch (Exception e)
      {
         LOG.warn("Could not delete {} due to {} ", key, e.getMessage());
         LOG.debug(e);
      }
      return false;
   }

   /**
    * Deletes all the provided key within the context of the given
    * bucket using the provided AS3
    * @param as3 the AS3 to use for the deletion
    * @param bucket the bucket in which we do the deletion
    * @param keys the keys to delete
    * @return the {@link List} of keys that could not be deleted
    */
   public static List<String> delete(AmazonS3 as3, String bucket, String... keys)
   {
      if (LOG.isDebugEnabled())
      {
         LOG.debug("delete >>> " + bucket + " " + keys);
      }

      List<String> result = null;
      DeleteObjectsRequest request = new DeleteObjectsRequest(bucket);
      request.withKeys(keys);

      // respect the order of modifications, as the deleting values still can be in
      // process of modification which depends on their size and network 
      for (String key : keys)
      {
         writeLock(bucket, key, request);
      }

      try
      {
         as3.deleteObjects(request);
      }
      catch (MultiObjectDeleteException e)
      {
         result = new ArrayList<String>();
         for (DeleteError deleteError : e.getErrors())
         {
            result.add(deleteError.getKey());
         }
      }
      catch (Exception e)
      {
         LOG.warn("Could not delete the keys {} due to {} ", Arrays.toString(keys), e.getMessage());
         LOG.debug(e);
      }
      return result;
   }

   /**
    * Indicates whether a given key exists within the context of the given
    * bucket using the provided AS3
    * @param as3 the AS3 to use for the lookup
    * @param bucket the bucket in which we do the lookup
    * @param key the key to check
    * @return <code>true</code> if it exists, <code>false</code> otherwise
    */
   public static boolean exists(AmazonS3 as3, String bucket, String key)
   {
      if (LOG.isDebugEnabled())
      {
         LOG.debug("exists >>> " + bucket + " " + key);
      }

      GetObjectMetadataRequest request = new GetObjectMetadataRequest(bucket, key);

      metadataLock(bucket, key, request);

      try
      {
         return as3.getObjectMetadata(request) != null;
      }
      catch (AmazonServiceException e)
      {
         if (e.getStatusCode() == 404)
            return false;
         throw e;
      }
   }

   /**
    * Gives the list of all keys that starts with the provided prefix within
    * the context of the given bucket using the provided AS3 
    * @param as3 the AS3 to use for the retrieval of the keys
    * @param bucket the bucket in which we do the retrieval of the keys
    * @param prefix all resulting keys must start with this prefix
    * @return a {@link List} of keys that starts with the provided prefix
    */
   public static List<String> getKeys(AmazonS3 as3, String bucket, String prefix)
   {
      if (LOG.isDebugEnabled())
      {
         LOG.debug("getKeys >>> " + bucket + " " + prefix);
      }

      ListObjectsRequest request = new ListObjectsRequest(bucket, prefix, null, null, null);

      String vidp = vid(bucket, prefix);
      for (String vid : processes.keySet())
      {
         if (vid.startsWith(vidp))
         {
            metadataLock(bucket, vid.substring(bucket.length()), request);
         }
      }

      ObjectListing objectListing = as3.listObjects(request);

      List<String> result = new ArrayList<String>();
      List<S3ObjectSummary> summaries = objectListing.getObjectSummaries();
      do
      {
         for (S3ObjectSummary objectSummary : summaries)
         {
            result.add(objectSummary.getKey());
         }
         if (objectListing.isTruncated())
            objectListing = as3.listNextBatchOfObjects(objectListing);
      }
      while (objectListing.isTruncated());

      return result;
   }

   /**
    * Copies a key from a location to another
    * @param as3 the AS3 to use for the copy
    * @param bucket the bucket in which we do the copy
    * @param sourceKey the source key
    * @param destinationKey the destination key
    */
   public static void copy(AmazonS3 as3, String bucket, String sourceKey, String destinationKey)
   {
      if (LOG.isDebugEnabled())
      {
         LOG.debug("copy >>> " + bucket + " " + sourceKey + " " + destinationKey);
      }

      CopyObjectRequest request = new CopyObjectRequest(bucket, sourceKey, bucket, destinationKey);

      // respect the order of modifications, as the copying value still can be in
      // process of modification which depends on its size and network 
      readLock(bucket, sourceKey, request); // source for read
      writeLock(bucket, destinationKey, request); // destination on write

      as3.copyObject(request);
   }

   /**
    * Stores a given content for the provided key within the context of the given
    * bucket using the provided AS3
    * @param as3 the AS3 to use for the put
    * @param bucket the bucket in which we do the put
    * @param key the key to add
    * @param content the content of the key to add
    * @throws IOException If an error prevents to get the size of the content
    */
   public static void writeValue(AmazonS3 as3, String bucket, String key, InputStream content) throws IOException
   {
      if (LOG.isDebugEnabled())
      {
         LOG.debug("writeValue >>> " + bucket + " " + key);
      }

      ObjectMetadata metadata = new ObjectMetadata();
      metadata.setContentLength(content.available());
      // TODO set content type,encoding,disposition if applicable
      PutObjectRequest request = new PutObjectRequest(bucket, key, content, metadata);

      // respect the order of creation and further modifications, as the writting values still can be in
      // process of previous uploading which depends on their size and network 
      writeLock(bucket, key, request);

      as3.putObject(request);
   }

   /**
    * Reads the content of a provided key within the context of the given
    * bucket using the provided AS3
    * @param as3 the AS3 to use for the get
    * @param bucket the bucket in which we do the get
    * @param key the key for which we extract the content
    * @param type the type of the corresponding property
    * @param orderNumber the order number of the corresponding property
    * @param spoolConfig the configuration for spooling
    * @return the corresponding {@link ValueData}
    * @throws IOException if an error occurs
    */
   public static ValueData readValueData(AmazonS3 as3, String bucket, String key, int type, int orderNumber,
      SpoolConfig spoolConfig) throws IOException
   {
      if (LOG.isDebugEnabled())
      {
         LOG.debug("readValueData >>> " + bucket + " " + key);
      }

      try
      {
         GetObjectRequest request = new GetObjectRequest(bucket, key);

         S3Process process = readLock(bucket, key, request);

         S3Object object = as3.getObject(request);

         process.addResource(object); // object will be closed on transfer completed

         long fileSize = object.getObjectMetadata().getContentLength();

         if (fileSize > spoolConfig.maxBufferSize)
         {
            SwapFile swapFile =
               SwapFile.get(spoolConfig.tempDirectory, key.replace('/', '_') + "." + System.currentTimeMillis() + "_"
                  + SEQUENCE.incrementAndGet(), spoolConfig.fileCleaner);
            if (!swapFile.isSpooled())
            {
               // spool S3 Value content into swap file
               try
               {
                  InputStream is = object.getObjectContent();
                  FileOutputStream fout = new FileOutputStream(swapFile);
                  ReadableByteChannel inch = Channels.newChannel(is);
                  try
                  {
                     FileChannel fch = fout.getChannel();
                     long actualSize = fch.transferFrom(inch, 0, fileSize);

                     if (fileSize != actualSize)
                        throw new IOException("Actual S3 Value size (" + actualSize + ") and content-length ("
                           + fileSize + ") differs. S3 key " + key);
                  }
                  finally
                  {
                     inch.close();
                     fout.close();
                     is.close(); // close S3 stream explicitly
                  }
               }
               finally
               {
                  swapFile.spoolDone();
               }
            }

            return new CleanableFilePersistedValueData(orderNumber, swapFile, spoolConfig);
         }
         else
         {
            InputStream is = object.getObjectContent();
            try
            {
               byte[] data = new byte[(int)fileSize];
               byte[] buff =
                  new byte[ValueFileIOHelper.IOBUFFER_SIZE > fileSize ? ValueFileIOHelper.IOBUFFER_SIZE : (int)fileSize];

               int rpos = 0;
               int read;

               while ((read = is.read(buff)) >= 0)
               {
                  System.arraycopy(buff, 0, data, rpos, read);
                  rpos += read;
               }

               return ValueDataUtil.createValueData(type, orderNumber, data);
            }
            finally
            {
               is.close();
            }
         }
      }
      catch (AmazonServiceException e)
      {
         if (e.getStatusCode() == 404)
            throw new FileNotFoundException("Could not find the key " + key + ": " + e.getMessage());
         throw e;
      }
   }

   /**
    * Gives the content length of the corresponding resource 
    * within the context of the given bucket using the provided AS3
    * @param as3 the AS3 to use for the content length retrieval
    * @param bucket the bucket in which we do the content length retrieval
    * @param key the key of the resource for which we want the content length
    * @return the content length of the resource or -1 if it is unknown
    */
   public static long getContentLength(AmazonS3 as3, String bucket, String key)
   {
      if (LOG.isDebugEnabled())
      {
         LOG.debug("getContentLength >>> " + bucket + " " + key);
      }

      GetObjectMetadataRequest request = new GetObjectMetadataRequest(bucket, key);

      metadataLock(bucket, key, request);

      try
      {
         ObjectMetadata metadata = as3.getObjectMetadata(request);
         return metadata.getContentLength();
      }
      catch (AmazonServiceException e)
      {
         if (e.getStatusCode() == 404)
            return -1;
         throw e;
      }
   }

   /**
    * Gives the content of the corresponding resource within the context of the given 
    * bucket using the provided AS3
    * @param as3 the AS3 to use for the content retrieval
    * @param bucket the bucket in which we do the content retrieval
    * @param key the key of the resource for which we want the content
    * @param start the start position in case we want to get a sub part of the content
    * or -1 otherwise
    * @param end the end position in case we want to get a sub part of the content
    * or -1 otherwise
    * @return the content of the corresponding resource or a sub part of it
    */
   public static InputStream getContent(AmazonS3 as3, String bucket, String key, long start, long end)
   {
      if (LOG.isDebugEnabled())
      {
         LOG.debug("getContent >>> " + bucket + " " + key);
      }

      GetObjectRequest request = new GetObjectRequest(bucket, key);

      S3Process process = readLock(bucket, key, request);

      if (start > 0 && end > 0)
      {
         request.setRange(start, end);
      }

      S3Object object = as3.getObject(request);

      // this object will be closed when its data transfer complete (via S3 listener)
      process.addResource(object);

      return object.getObjectContent();
   }

   // ***** internals *****

   static String vid(String bucket, String key)
   {
      return bucket + key;
   }

   static S3Process getProcess(String pid)
   {
      return processes.get(pid);
   }

   static boolean removeProcess(S3Process process)
   {
      return processes.remove(process.getId(), process);
   }

   static S3Process addProcess(S3Process process)
   {
      S3Process previous = processes.putIfAbsent(process.getId(), process);
      if (previous != null && previous != process)
      {
         // wait for previous (e.g. placed during this method work) and add the created process exclusively 
         synchronized (processes)
         {
            previous.waitRead(); // wait for previous write
            return processes.put(process.getId(), process); // add new process
         }
      }

      return previous;
   }

   /**
    * Wait for value write completed for key, if it has a place, and place a new write lock for it. 
    * Both locks will be added as listeners to given S3 request for cleanup on completion.
    * 
    * @param bucket {@link String} bucket name
    * @param key {@link String} value key
    * @param request {@link AmazonWebServiceRequest}
    * @return {@link S3Process} write process created for the key
    */
   private static S3Process writeLock(String bucket, String key, AmazonWebServiceRequest request)
   {
      String vid = vid(bucket, key);
      S3Process process = getProcess(vid);
      if (process == null)
      {
         // write lock until the value will be modified or deleted 
         if (LOG.isDebugEnabled())
         {
            LOG.debug(">>> writeLock new for " + vid);
         }
         process = new S3Process(vid);
      }
      else
      {
         // reuse existing process and unlock read to be able lock for write by this thread
         if (LOG.isDebugEnabled())
         {
            LOG.debug(">>> writeLock wait for " + vid);
         }
      }

      // wait for all writes and reads and place exclusive lock
      process.lockWrite();

      // process will be unlocked by S3 listener
      setRequestListener(request, process);

      // finally add to global processes
      addProcess(process);

      if (LOG.isDebugEnabled())
      {
         LOG.debug("<<< writeLock acquired " + process.getId());
      }
      return process;
   }

   /**
    * Wait for value write completed for key1, if it has a place, and place a new write lock for key2. 
    * Both locks will be added as listeners to given S3 request for release on completion.
    * If key1 and key2 differ it is a copy usecase. 
    * 
    * @param bucket {@link String} bucket name
    * @param key1 {@link String} source key
    * @param key2 {@link String} destination name
    * @param request {@link AmazonWebServiceRequest}
    * @return {@link S3Process} write process created for key2
    */
   @Deprecated
   private static S3Process writeLock(String bucket, String key1, String key2, AmazonWebServiceRequest request)
   {
      S3Process process;
      if (key1.equals(key2))
      {
         process = getProcess(vid(bucket, key1));
         // TODO cleanup: existing.unlockRead();
      }
      else
      {
         // usecase of copy - we lock
         process = readLock(bucket, key1, request);
      }

      if (process == null)
      {
         // write lock until the value will be modified or deleted 
         if (LOG.isDebugEnabled())
         {
            LOG.debug(">>> writeLock new for " + vid(bucket, key2));
         }
         process = new S3Process(vid(bucket, key2));
      }
      else
      {
         // reuse existing process and unlock read to be able lock for write by this thread
         if (LOG.isDebugEnabled())
         {
            LOG.debug(">>> writeLock existing for " + process.getId());
         }
      }

      // wait for all writes and reads and place exclusive lock
      process.lockWrite();

      // process will be unlocked by S3 listener
      setRequestListener(request, process);

      // finally add to global processes
      addProcess(process);

      if (LOG.isDebugEnabled())
      {
         LOG.debug("<<< writeLock acquired " + process.getId());
      }
      return process;
   }

   /**
    * Wait for value write completed and place read lock for metadata of a value.
    * 
    * @param bucket {@link String} bucket name
    * @param key {@link String} value key
    * @param request {@link AmazonWebServiceRequest}
    * @return {@link S3Process} process existing for key or <code>null</code>
    */
   private static S3Process metadataLock(String bucket, String key, AmazonWebServiceRequest request)
   {
      return readLock(bucket, key, request, true);
   }

   /**
    * Wait for value write completed and place read lock for it.
    * 
    * @param bucket {@link String} bucket name
    * @param key {@link String} value key
    * @param request {@link AmazonWebServiceRequest}
    * @return {@link S3Process} process existing for key or <code>null</code>
    */
   private static S3Process readLock(String bucket, String key, AmazonWebServiceRequest request)
   {
      return readLock(bucket, key, request, false);
   }

   /**
   * Wait for value write completed and place read lock for it.
   * 
   * @param bucket {@link String} bucket name
   * @param key {@link String} value key
   * @param request {@link AmazonWebServiceRequest}
   * @return {@link S3Process} process existing for key or <code>null</code>
   */
   private static S3Process readLock(String bucket, String key, AmazonWebServiceRequest request, boolean metadata)
   {
      String vid = vid(bucket, key);
      S3Process process = getProcess(vid);
      if (process != null)
      {
         // wait until the value will be fully written, see writeValue()
         // process will be unlocked by S3 listener
         if (LOG.isDebugEnabled())
         {
            LOG.debug(">>> readLock wait for " + vid);
         }
         process.lockRead(metadata);
         setRequestListener(request, process);
      }
      else
      {
         // create new process and lock it for reading, then only (!) add it to the request and processes
         if (LOG.isDebugEnabled())
         {
            LOG.debug(">>> readLock new for " + vid);
         }
         process = new S3Process(vid);
         process.lockRead(metadata);
         setRequestListener(request, process);
         addProcess(process);
      }
      if (LOG.isDebugEnabled())
      {
         LOG.debug("<<< readLock acquired " + process.getId());
      }
      return process;
   }

   /**
    * Set given {@link S3Process} as a progress listener of given S3 request. If the request already has a listener, 
    * it will be replaced by given process chained to the original listener. 
    * 
    * @param request {@link AmazonWebServiceRequest}
    * @param process {@link S3Process} 
    */
   private static void setRequestListener(AmazonWebServiceRequest request, S3Process process)
   {
      ProgressListener listener = request.getGeneralProgressListener();
      if (listener != null && listener != ProgressListener.NOOP)
      {
         if (listener != process)
         {
            // this way we create a chain of process listeners for multi-object requests
            process.setParent(listener);
            request.setGeneralProgressListener(process);
         }
      }
      else
      {
         request.setGeneralProgressListener(process);
      }
   }
}

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

import com.amazonaws.services.s3.AmazonS3;

import org.exoplatform.services.jcr.datamodel.ValueData;
import org.exoplatform.services.jcr.impl.dataflow.SpoolConfig;
import org.exoplatform.services.jcr.impl.storage.value.ValueDataNotFoundException;
import org.exoplatform.services.jcr.impl.storage.value.ValueOperation;
import org.exoplatform.services.jcr.impl.storage.value.operations.ValueURLIOHelper;
import org.exoplatform.services.jcr.impl.util.io.FileCleaner;
import org.exoplatform.services.jcr.storage.value.ValueIOChannel;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author <a href="mailto:nfilotto@exoplatform.com">Nicolas Filotto</a>
 * @version $Id$
 *
 */
public class S3ValueIOChannel implements ValueIOChannel
{
   private static final Log LOG = ExoLogger.getLogger("exo.jcr.component.core.S3ValueIOChannel");

   private static final AtomicLong SEQUENCE = new AtomicLong();

   /**
    * The client that we use to access to Amazon S3
    */
   private AmazonS3 as3;

   /**
    * Bucket name. See <a href="http://aws.amazon.com/fr/documentation/s3/">Amazon S3</a>
    */
   private final String bucket;

   /**
    * The prefix to use for all the keys, this is needed to be able to
    * use the same bucket for several workspaces
    */
   private final String keyPrefix;

   /**
    * The file cleaner
    */
   private final FileCleaner cleaner;

   /**
    * Changes to be saved on commit or rolled back on rollback.
    */
   private final List<ValueOperation> changes = new ArrayList<ValueOperation>();

   /**
    * The storage id
    */
   private final S3ValueStorage storage;

   S3ValueIOChannel(S3ValueStorage storage)
   {
      this.storage = storage;
      this.as3 = storage.getAs3();
      this.bucket = storage.getBucket();
      this.keyPrefix = storage.getKeyPrefix();
      this.cleaner = storage.getCleaner();
   }

   /**
    * {@inheritDoc}
    */
   public ValueData read(String propertyId, int orderNumber, int type, SpoolConfig spoolConfig) throws IOException
   {
      String key = getKey(propertyId, orderNumber);
      try
      {
         return S3ValueUtil.readValueData(as3, bucket, key, type, orderNumber, spoolConfig);
      }
      catch (Exception e)
      {
         throw new IOException("Could not read the content of the key " + key, e);
      }
   }

   /**
    * {@inheritDoc}
    */
   public void checkValueData(String propertyId, int orderNumber) throws ValueDataNotFoundException, IOException
   {
      String key = getKey(propertyId, orderNumber);
      if (!S3ValueUtil.exists(as3, bucket, key))
      {
         throw new ValueDataNotFoundException("Value data corresponding to property with [id=" + propertyId
            + ", ordernum=" + orderNumber + "] does not exist.");
      }
   }

   /**
    * {@inheritDoc}
    */
   public void repairValueData(String propertyId, int orderNumber) throws IOException
   {
      String key = getKey(propertyId, orderNumber);
      try
      {
         S3ValueUtil.writeValue(as3, bucket, key, new ByteArrayInputStream(new byte[0]));
      }
      catch (Exception e)
      {
         throw new IOException("Could not create an empty content for the property/order"
            + " number corresponding to the key " + key, e);
      }
   }

   /**
    * {@inheritDoc}
    */
   public void write(String propertyId, ValueData value) throws IOException
   {
      ValueOperation o = new WriteOperation(propertyId, value);
      o.execute();
      changes.add(o);
   }

   /**
    * {@inheritDoc}
    */
   public void delete(String propertyId) throws IOException
   {
      ValueOperation o = new DeleteOperation(propertyId);
      o.execute();
      changes.add(o);
   }

   /**
    * {@inheritDoc}
    */
   public void close()
   {
   }

   /**
    * {@inheritDoc}
    */
   public String getStorageId()
   {
      return storage.getId();
   }

   /**
    * {@inheritDoc}
    */
   public void prepare() throws IOException
   {
      for (ValueOperation vo : changes)
         vo.prepare();
   }

   /**
    * {@inheritDoc}
    */
   public void commit() throws IOException
   {
      try
      {
         for (ValueOperation vo : changes)
            vo.commit();
      }
      finally
      {
         changes.clear();
      }
   }

   /**
    * {@inheritDoc}
    */
   public void twoPhaseCommit() throws IOException
   {
      try
      {
         for (ValueOperation vo : changes)
            vo.twoPhaseCommit();
      }
      finally
      {
         changes.clear();
      }
   }

   /**
    * {@inheritDoc}
    */
   public void rollback() throws IOException
   {
      try
      {
         for (int p = changes.size() - 1; p >= 0; p--)
            changes.get(p).rollback();
      }
      finally
      {
         changes.clear();
      }
   }
   /**
    * Gives the key corresponding to the provided property id and order number
    */
   private String getKey(String propertyId, int orderNumber)
   {
      StringBuilder key = new StringBuilder(64);
      key.append(buildKeyPrefix(propertyId));
      key.append(propertyId);
      key.append(orderNumber);
      return key.toString();
   }

   protected String buildKeyPrefix(String propertyId)
   {
      final int xLength = 8;
      char[] chs = propertyId.toCharArray();
      StringBuilder key = new StringBuilder();
      if (keyPrefix != null)
         key.append(keyPrefix);
      for (int i = 0; i < xLength; i++)
      {
         key.append('/').append(chs[i]);
      }
      key.append(propertyId.substring(xLength));
      key.append('/');
      return key.toString();
   }

   /**
    * Copy the content of the source key to the destination key and 
    * remove the source key. If the source key cannot be deleted 
    * it will be given to the file cleaned
    * @param srcKey the key to move
    * @param destKey the destination key
    * @throws IOException if error occurs
    */
   protected void move(String srcKey, String destKey) throws IOException
   {
      try
      {
         S3ValueUtil.copy(as3, bucket, srcKey, destKey);
      }
      catch (Exception e)
      {
         throw new IOException("Could not move the file from " + srcKey + " to " + destKey, e);
      }
      if (!S3ValueUtil.delete(as3, bucket, srcKey))
      {
         if (LOG.isDebugEnabled())
         {
            LOG.debug("The file '" + srcKey + "' could not be deleted which prevents the application"
               + " to move it properly. The file will be given to the file cleaner for a later deletion.");
         }
         // The source could not be deleted so we add it to the  
         // file cleaner  
         cleaner.addFile(new S3File(bucket, srcKey, as3));
      }
   }

   /**
    * DeleteOperation.
    */
   private class DeleteOperation implements ValueOperation
   {

      private final String propertyId;

      private List<String> keys;

      private String[] bckKeys;

      DeleteOperation(String propertyId)
      {
         this.propertyId = propertyId;
      }

      /**
       * {@inheritDoc}
       */
      public void commit() throws IOException
      {
         try
         {
            prepare();
         }
         finally
         {
            twoPhaseCommit();
         }
      }

      /**
       * {@inheritDoc}
       */
      public void rollback() throws IOException
      {
         if (keys != null)
         {
            if (bckKeys == null)
               return;
            for (int i = 0, length = bckKeys.length; i < length; i++)
            {
               String key = bckKeys[i];
               if (key == null)
                  break;
               // As the files could be registered to the file cleaner 
               // to be removed in case of a move that failed
               // or in case of a WriteValue.rollback() that could not
               // remove the file, we need to unregister the files that
               // will be restored thanks to the backup file
               cleaner.removeFile(new S3File(bucket, key, as3));
               move(key, keys.get(i));
            }
         }
      }

      /**
       * {@inheritDoc}
       */
      public void execute() throws IOException
      {
         try
         {
            keys = S3ValueUtil.getKeys(as3, bucket, buildKeyPrefix(propertyId));
         }
         catch (Exception e)
         {
            throw new IOException("Could not get the keys related to the property " + propertyId, e);
         }
      }

      /**
        * {@inheritDoc}
        */
      public void prepare() throws IOException
      {
         if (keys != null)
         {
            bckKeys = new String[keys.size()];
            for (int i = 0, length = keys.size(); i < length; i++)
            {
               String key = keys.get(i);
               bckKeys[i] = key + "." + System.currentTimeMillis() + "_" + SEQUENCE.incrementAndGet();
               move(key, bckKeys[i]);
            }
         }
      }

      /**
         * {@inheritDoc}
       */
      public void twoPhaseCommit() throws IOException
      {
         if (keys != null)
         {
            if (bckKeys == null)
               return;
            int i = 0, length = bckKeys.length;
            for (; i < length; i++)
            {
               if (bckKeys[i] == null)
                  break;
            }
            if (i < length)
            {
               String[] nonNullBckKeys = new String[i];
               System.arraycopy(bckKeys, 0, nonNullBckKeys, 0, i);
               bckKeys = nonNullBckKeys;
            }
            List<String> keysNotYetDeleted = S3ValueUtil.delete(as3, bucket, bckKeys);
            if (keysNotYetDeleted != null)
            {
               for (String key : keysNotYetDeleted)
               {
                  cleaner.addFile(new S3File(bucket, key, as3));
               }
            }
         }
      }
   }

   /**
    * WriteOperation.
    * 
    */
   private class WriteOperation implements ValueOperation
   {

      /**
       * The key of the value.
       */
      private final String key;

      /**
       * Value.
       */
      private final ValueData value;

      /**
       * WriteOperation constructor.
       * 
       * @param propertyId
       *          String
       * @param value
       *          ValueData
       */
      WriteOperation(String propertyId, ValueData value)
      {
         this.key = getKey(propertyId, value.getOrderNumber());
         this.value = value;
      }

      /**
       * {@inheritDoc}
       */
      public void commit() throws IOException
      {
         try
         {
            prepare();
         }
         finally
         {
            twoPhaseCommit();
         }
      }

      /**
       * {@inheritDoc}
       */
      public void rollback() throws IOException
      {
         File file = new S3File(bucket, key, as3);
         if (!file.delete())
         {
            cleaner.addFile(file);
         }
      }

      /**
       * {@inheritDoc}
       */
      public void execute() throws IOException
      {
         // do nothing
      }

      /**
       * {@inheritDoc}
       */
      public void prepare() throws IOException
      {
         // Prevent the file cleaner to remove it
         cleaner.removeFile(new S3File(bucket, key, as3));

         try
         {
            // write value to the AS3
            S3ValueUtil.writeValue(as3, bucket, key, getContent());
         }
         catch (Exception e)
         {
            throw new IOException("Could not write the value of the property/order"
               + " number corresponding to the key " + key, e);
         }
      }

      private InputStream getContent() throws IOException
      {
         return ValueURLIOHelper.getContent(storage, value, key, true);
      }

      /**
       * {@inheritDoc}
       */
      public void twoPhaseCommit() throws IOException
      {
         // do nothing
      }

   }
}

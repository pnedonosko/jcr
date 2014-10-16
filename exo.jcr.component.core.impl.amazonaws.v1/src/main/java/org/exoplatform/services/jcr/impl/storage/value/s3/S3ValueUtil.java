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
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.DeleteObjectsRequest;
import com.amazonaws.services.s3.model.DeleteObjectsResult;
import com.amazonaws.services.s3.model.MultiObjectDeleteException;
import com.amazonaws.services.s3.model.MultiObjectDeleteException.DeleteError;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;
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

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
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
   private static final Log LOG = ExoLogger.getLogger("exo.jcr.component.core.S3ValueUtil");

   private static final AtomicLong SEQUENCE = new AtomicLong();

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
      DeleteObjectsRequest request = new DeleteObjectsRequest(bucket);
      request.withKeys(key);
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
      List<String> result = null;
      DeleteObjectsRequest request = new DeleteObjectsRequest(bucket);
      request.withKeys(keys);
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
      try
      {
         return as3.getObjectMetadata(bucket, key) != null;
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
      List<String> result = new ArrayList<String>();
      ObjectListing objectListing = as3.listObjects(bucket, prefix);
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
      as3.copyObject(bucket, sourceKey, bucket, destinationKey);
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
      ObjectMetadata metadata = new ObjectMetadata();
      metadata.setContentLength(content.available());
      as3.putObject(bucket, key, content, metadata);
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
      try
      {
         S3Object object = as3.getObject(bucket, key);
         long fileSize = object.getObjectMetadata().getContentLength();

         if (fileSize > spoolConfig.maxBufferSize)
         {
            SwapFile swapFile =
               SwapFile.get(spoolConfig.tempDirectory,
                  key.replace('/', '_') + "." + System.currentTimeMillis() + "_" + SEQUENCE.incrementAndGet(), spoolConfig.fileCleaner);
            // spool S3 Value content into swap file
            try
            {
               FileOutputStream fout = new FileOutputStream(swapFile);
               ReadableByteChannel inch = Channels.newChannel(object.getObjectContent());
               try
               {
                  FileChannel fch = fout.getChannel();
                  long actualSize = fch.transferFrom(inch, 0, fileSize);

                  if (fileSize != actualSize)
                     throw new IOException("Actual S3 Value size (" + actualSize + ") and content-length (" + fileSize
                        + ") differs. S3 key " + key);
               }
               finally
               {
                  inch.close();
                  fout.close();
               }
            }
            finally
            {
               swapFile.spoolDone();
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
      try
      {
         ObjectMetadata metadata = as3.getObjectMetadata(bucket, key);
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
    * @param spoolConfig the configuration for spooling
    * @return the content of the corresponding resource or a sub part of it
    * @throws IOException if an error occurs
    */
   public static InputStream getContent(AmazonS3 as3, String bucket, String key, SpoolConfig spoolConfig) throws IOException
   {
      try
      {
         S3Object object = as3.getObject(bucket, key);
         long fileSize = object.getObjectMetadata().getContentLength();

         if (fileSize > spoolConfig.maxBufferSize)
         {
            SwapFile swapFile =
               SwapFile.get(spoolConfig.tempDirectory,
                  key.replace('/', '_') + "." + System.currentTimeMillis() + "_" + SEQUENCE.incrementAndGet(), spoolConfig.fileCleaner);
            // spool S3 Value content into swap file
            try
            {
               FileOutputStream fout = new FileOutputStream(swapFile);
               ReadableByteChannel inch = Channels.newChannel(object.getObjectContent());
               try
               {
                  FileChannel fch = fout.getChannel();
                  long actualSize = fch.transferFrom(inch, 0, fileSize);

                  if (fileSize != actualSize)
                     throw new IOException("Actual S3 Value size (" + actualSize + ") and content-length (" + fileSize
                        + ") differs. S3 key " + key);
               }
               finally
               {
                  inch.close();
                  fout.close();
               }
            }
            finally
            {
               swapFile.spoolDone();
            }

            return new FileInputStream(swapFile);
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

               return new ByteArrayInputStream(data);
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
}

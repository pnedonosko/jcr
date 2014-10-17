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

import org.exoplatform.commons.utils.SecurityHelper;
import org.exoplatform.services.jcr.storage.value.ValueStorageURLConnection;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;

/**
 * This implementation of an {@link ValueStorageURLConnection} allows to get a content from
 * Amazon S3
 * 
 * @author <a href="mailto:nfilotto@exoplatform.com">Nicolas Filotto</a>
 * @version $Id$
 *
 */
class S3URLConnection extends ValueStorageURLConnection
{
   
   private static final Log LOG = ExoLogger.getLogger("exo.jcr.component.core.S3URLConnection");

   /**
    * This value is used to know whether a value has been defined or node
    */
   private static final int UNDEFINED = -16;

   /**
    * The client that we use to access to Amazon S3
    */
   private final AmazonS3 as3;

   /**
    * The bucket to use to access to Amazon S3
    */
   private final String bucket;

   /**
    * The content length of the resource
    */
   private int contentLength = UNDEFINED;

   /**
    * @param url
    */
   S3URLConnection(AmazonS3 as3, String bucket, URL url)
   {
      super(url);
      this.as3 = as3;
      this.bucket = bucket;
   }

   /**
    * @see java.net.URLConnection#connect()
    */
   @Override
   public void connect() throws IOException
   {
      this.connected = true;
   }

   /**
    * @see java.net.URLConnection#getContentLength()
    */
   @Override
   public int getContentLength()
   {
      if (contentLength == UNDEFINED)
      {
         contentLength = SecurityHelper.doPrivilegedAction(new PrivilegedAction<Integer>()
         {
            public Integer run()
            {
               return (int)S3ValueUtil.getContentLength(as3, bucket, idResource);
            }
         });
         if (contentLength == UNDEFINED)
            contentLength = -1;
      }

      return contentLength;
   }

   /**
    * @see java.net.URLConnection#getInputStream()
    */
   @Override
   public InputStream getInputStream() throws IOException
   {
      if (!connected)
         connect();
      return new InputStream()
      {
         private long start;

         private InputStream delegate;

         private int diff;

         @Override
         public int read() throws IOException
         {
            return SecurityHelper.doPrivilegedIOExceptionAction(new PrivilegedExceptionAction<Integer>()
            {

               public Integer run() throws Exception
               {
                  if (delegate == null)
                  {
                     delegate = S3ValueUtil.getContent(as3, bucket, idResource, -1, -1);
                     if (start > 0)
                     {
                        delegate.skip(start);
                     }
                  }
                  int result = delegate.read();
                  if (result != -1)
                  {
                     diff--;
                  }
                  return result;
               }
            });
         }

         /**
          * @see java.io.InputStream#available()
          */
         @Override
         public int available() throws IOException
         {
            int available = getContentLength();
            available += diff;
            return available <= 0 ? 0 : available;
         }

         /**
          * @see java.io.InputStream#read(byte[], int, int)
          */
         @Override
         public int read(final byte[] b, final int off, final int len) throws IOException
         {
            return SecurityHelper.doPrivilegedIOExceptionAction(new PrivilegedExceptionAction<Integer>()
            {

               public Integer run() throws Exception
               {
                  if (start > 0)
                  {
                     if (LOG.isDebugEnabled())
                     {
                        LOG.debug("???? Read with skipping " + start);
                     }
                     try
                     {
                        delegate = S3ValueUtil.getContent(as3, bucket, idResource, start, start + len);
                        int result = delegate.read(b, off, len);
                        if (result != -1)
                        {
                           start += result;
                           diff -= result;
                           while (result < len)
                           {
                              int delta = delegate.read(b, off + result, len - result);
                              if (delta == -1)
                              {
                                 break;
                              }
                              start += delta;
                              diff -= delta;
                              result += delta;
                           }
                        }
                        return result;
                     }
                     finally
                     {
                        delegate.close();
                        delegate = null;
                     }
                  } // TODO seems here we need else-block where we'll read w/o requested skipped
                  if (delegate == null)
                  {
                     delegate = S3ValueUtil.getContent(as3, bucket, idResource, -1, -1);
                  }
                  try
                  {
                     int result = delegate.read(b, off, len);
                     if (result != -1)
                     {
                        diff -= result;
                        while (result < len)
                        {
                           int delta = delegate.read(b, off + result, len - result);
                           if (delta == -1)
                           {
                              break;
                           }
                           diff -= delta;
                           result += delta;
                        }
                     }
                     return result;
                  }
                  catch(IOException e)
                  {
                     LOG.error("Error reading S3 value from " + bucket + " " + idResource + ": " + e); // TODO cleanup
                     throw e;
                  }
               }
            });

         }

         /**
          * @see java.io.InputStream#skip(long)
          */
         @Override
         public long skip(long n) throws IOException
         {
            if (n <= 0)
               return 0;
            diff -= n;
            return start = n;
         }

         /**
          * @see java.io.InputStream#close()
          */
         @Override
         public void close() throws IOException
         {
            if (delegate != null)
            {
               delegate.close();
            }
         }
      };
   }
}

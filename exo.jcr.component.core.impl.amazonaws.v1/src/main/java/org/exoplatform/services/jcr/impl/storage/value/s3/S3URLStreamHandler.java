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

import org.exoplatform.services.jcr.storage.value.ValueStorageURLConnection;
import org.exoplatform.services.jcr.storage.value.ValueStorageURLStreamHandler;

import java.io.IOException;
import java.net.URL;

/**
 * 
 * A specific {@link ValueStorageURLStreamHandler} that knows how to
 * get a content from Amazon S3
 *  
 * @author <a href="mailto:nfilotto@exoplatform.com">Nicolas Filotto</a>
 * @version $Id$
 *
 */
class S3URLStreamHandler extends ValueStorageURLStreamHandler
{
   /**
    * The client that we use to access to Amazon S3
    */
   private final AmazonS3 as3;

   /**
    * The bucket to use to access to Amazon S3
    */
   private final String bucket;

   /**
    * @param url
    */
   S3URLStreamHandler(AmazonS3 as3, String bucket)
   {
      this.as3 = as3;
      this.bucket = bucket;
   }

   /**
    * @see org.exoplatform.services.jcr.storage.value.ValueStorageURLStreamHandler#createURLConnection(java.net.URL, java.lang.String, java.lang.String, java.lang.String)
    */
   @Override
   protected ValueStorageURLConnection createURLConnection(URL u, String repository, String workspace,
      String valueStorageId) throws IOException
   {
      return new S3URLConnection(as3, bucket, u);
   }
}

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

import java.io.File;

/**
 * An abstract representation of file on Amazon S3.
 * 
 * @author <a href="mailto:nfilotto@exoplatform.com">Nicolas Filotto</a>
 * @version $Id$
 *
 */
public class S3File extends File
{
   /**
    * The serial version UID
    */
   private static final long serialVersionUID = 8311512007067806494L;

   /**
    * The client that we use to access to Amazon S3
    */
   private final AmazonS3 as3;

   /**
    * The bucket to use to access to Amazon S3
    */
   private final String bucket;

   /**
    * The key corresponding to the resource
    */
   private final String key;

   public S3File(String bucket, String key, AmazonS3 as3)
   {
      super(bucket, key);
      this.as3 = as3;
      this.bucket = bucket;
      this.key = key;
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public boolean isDirectory()
   {
      return false;
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public boolean isFile()
   {
      return true;
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public boolean delete()
   {
      return S3ValueUtil.delete(as3, bucket, key);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public boolean exists()
   {
      try
      {
         return S3ValueUtil.exists(as3, bucket, key);
      }
      catch (Exception e)
      {
         // If we cannot know if it exists or not, we will assume that it doesn't exist
         return false;
      }
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public int hashCode()
   {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((bucket == null) ? 0 : bucket.hashCode());
      result = prime * result + ((key == null) ? 0 : key.hashCode());
      return result;
   }

   /**
    * @see java.lang.Object#equals(java.lang.Object)
    */
   @Override
   public boolean equals(Object obj)
   {
      if (this == obj)
         return true;
      if (!super.equals(obj))
         return false;
      if (getClass() != obj.getClass())
         return false;
      S3File other = (S3File)obj;
      if (bucket == null)
      {
         if (other.bucket != null)
            return false;
      }
      else if (!bucket.equals(other.bucket))
         return false;
      if (key == null)
      {
         if (other.key != null)
            return false;
      }
      else if (!key.equals(other.key))
         return false;
      return true;
   }

}

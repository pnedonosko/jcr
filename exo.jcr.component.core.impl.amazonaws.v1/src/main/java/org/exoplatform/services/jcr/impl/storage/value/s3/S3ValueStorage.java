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

import org.exoplatform.services.jcr.config.RepositoryConfigurationException;
import org.exoplatform.services.jcr.impl.storage.value.ValueDataResourceHolder;
import org.exoplatform.services.jcr.impl.util.io.FileCleaner;
import org.exoplatform.services.jcr.storage.WorkspaceStorageConnection;
import org.exoplatform.services.jcr.storage.value.ValueIOChannel;
import org.exoplatform.services.jcr.storage.value.ValueStoragePlugin;
import org.exoplatform.services.jcr.storage.value.ValueStorageURLConnection;
import org.exoplatform.services.jcr.storage.value.ValueStorageURLStreamHandler;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;

import javax.naming.InitialContext;
import javax.naming.NamingException;

/**
 * This class is the implementation of a value storage based on Amazon S3
 * 
 * @author <a href="mailto:nfilotto@exoplatform.com">Nicolas Filotto</a>
 * @version $Id$
 *
 */
public class S3ValueStorage extends ValueStoragePlugin
{
   private static final Log LOG = ExoLogger.getLogger("exo.jcr.component.core.S3ValueStorage");

   /**
    * The name of the bucket.
    */
   private final static String BUCKET = "bucket";

   /**
    * The key prefix, this is used when for the same bucket name
    * we would like to store content of different workspace
    */
   private final static String KEY_PREFIX = "key-prefix";

   /**
    * The name of the datasource to use to access to AS3
    */
   private final static String AS3_SOURCE_NAME = "as3-source-name";

   private String bucket;

   private AmazonS3 as3;

   private String keyPrefix;

   private S3URLStreamHandler handler;

   private final FileCleaner cleaner;

   public S3ValueStorage(FileCleaner cleaner)
   {
      this.cleaner = cleaner;
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public void init(Properties props, ValueDataResourceHolder resources) throws RepositoryConfigurationException,
      IOException
   {
      bucket = props.getProperty(BUCKET);
      String keyPrefix = props.getProperty(KEY_PREFIX);
      setKeyPrefix(keyPrefix == null || keyPrefix.isEmpty() ? getRepository() + "/" + getWorkspace() + "/" + getId()
         : keyPrefix);
      String dataSourceName = props.getProperty(AS3_SOURCE_NAME);
      try
      {
         InitialContext ctx = new InitialContext();
         as3 = (AmazonS3)ctx.lookup(dataSourceName);
      }
      catch (NamingException e)
      {
         throw new RepositoryConfigurationException("Cannot access to the data source '" + dataSourceName + "'", e);
      }
      createBucketIfNeeded();
      handler = new S3URLStreamHandler(as3, bucket);
   }

   /**
    * Sets the key prefix
    */
   private void setKeyPrefix(String keyPrefix)
   {
      if (keyPrefix != null)
      {
         keyPrefix = escape(keyPrefix);
         while (keyPrefix.startsWith("/"))
         {
            keyPrefix = keyPrefix.substring(1);
         }
         if (!keyPrefix.isEmpty())
         {
            while (keyPrefix.endsWith("/"))
            {
               keyPrefix = keyPrefix.substring(0, keyPrefix.length() - 1);
            }
            if (!keyPrefix.isEmpty())
            {
               LOG.debug("A key prefix has been defined to {}", keyPrefix);
               this.keyPrefix = keyPrefix;
            }
         }
      }
   }

   private String escape(String keyPrefix)
   {
      int length = keyPrefix.length();
      StringBuilder result = new StringBuilder(length);
      for (int i = 0; i < length; i++)
      {
         char c = keyPrefix.charAt(i);
         switch (c)
         {
            case '\\' :
               result.append('/');
               break;
            case ' ' :
            case '%' :
            case ';' :
            case ',' :
            case '(' :
            case ')' :
            case '&' :
            case '#' :
            case '<' :
            case '>' :
            case ':' :
            case '"' :
            case '*' :
            case '?' :
            case '|' :
            case '.' :
               result.append('_');
               break;
            default :
               result.append(c);
         }
      }
      return result.toString();
   }

   /**
    * Creates the bucked if it doesn't exist yet
    */
   private void createBucketIfNeeded() throws RepositoryConfigurationException
   {
      try
      {
         if (!as3.doesBucketExist(bucket))
         {
            as3.createBucket(bucket);
         }
      }
      catch (Exception e)
      {
         throw new RepositoryConfigurationException("Could not check if the bucket exists or create it", e);
      }

   }

   /**
    * {@inheritDoc}
    */
   public ValueIOChannel openIOChannel() throws IOException
   {
      return new S3ValueIOChannel(this, as3, bucket, keyPrefix, cleaner);
   }

   /**
    * {@inheritDoc}
    */
   public void checkConsistency(WorkspaceStorageConnection dataConnection)
   {
   }

   /**
    * {@inheritDoc}
    */
   public boolean isSame(String storageId)
   {
      return getId().equals(storageId);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public ValueStorageURLConnection createURLConnection(URL u) throws IOException
   {
      return handler.createURLConnection(u, null, null, null);
   }

   /**
    * Creates a new URL from the provided key based on the local
    * {@link S3URLStreamHandler}
    * @throws MalformedURLException If the URL could not be created
    */
   protected URL createURL(String key) throws MalformedURLException
   {
      StringBuilder url = new StringBuilder(64);
      url.append(ValueStorageURLStreamHandler.PROTOCOL);
      url.append(":/");
      url.append(repository);
      url.append('/');
      url.append(workspace);
      url.append('/');
      url.append(id);
      url.append('/');
      url.append(key);
      return new URL(null, url.toString(), handler);
   }
}

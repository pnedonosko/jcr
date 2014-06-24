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

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;

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
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;

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

   private final static String KEY_PREFIX = "key-prefix";

   private final static String BUCKET = "bucket";

   private final static String REGION = "region";

   private final static String ACCESS_KEY_ID = "access-key-id";

   private final static String SECRET_KEY = "secret-access-key";

   private final static String CONNECTION_TIMEOUT = "connection-timeout";

   private final static String MAX_CONNECTIONS = "max-connections";

   private final static String MAX_ERROR_RETRY = "max-error-retry";

   private final static String SOCKET_TIMEOUT = "socket-timeout";

   private final static String SOCKET_SEND_BUFFER_SIZE_HINT = "socket-send-buffer-size-hint";

   private final static String SOCKET_RECEIVE_BUFFER_SIZE_HINT = "socket-receive-buffer-size-hint";

   private final static String LOCAL_ADDRESS = "local-address";

   private final static String PROTOCOL = "protocol";

   private final static String SIGNER_OVERRIDE = "signer-override";

   private final static String USER_AGENT = "user-agent";

   private final static String PROXY_HOST = "proxy-host";

   private final static String PROXY_PORT = "proxy-port";

   private final static String PROXY_DOMAIN = "proxy-domain";

   private final static String PROXY_USERNAME = "proxy-username";

   private final static String PROXY_PASSWORD = "proxy-password";

   private final static String PROXY_WORKSTATION = "proxy-workstation";

   private final static String PREEMPTIVE_BASIC_PROXY_AUTH = "preemptive-basic-proxy-auth";

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
      if (keyPrefix != null)
      {
         keyPrefix = keyPrefix.replace('\\', '/');
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
      String accessKeyId = props.getProperty(ACCESS_KEY_ID);
      String secretAccessKey = props.getProperty(SECRET_KEY);
      ClientConfiguration config = createClientConfiguration(props);
      as3 = createAmazonS3(config, accessKeyId, secretAccessKey);
      String region = props.getProperty(REGION);
      if (region != null && !region.isEmpty())
      {
         LOG.debug("The parameter {} has been set to {}", REGION, region);
         setRegion(region);
      }
      createBucketIfNeeded();
      handler = new S3URLStreamHandler(as3, bucket);
   }

   private void setRegion(String region) throws RepositoryConfigurationException
   {
      try
      {
         as3.setRegion(RegionUtils.getRegion(region));
      }
      catch (Exception e)
      {
         throw new RepositoryConfigurationException("Could not set the region", e);
      }
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
    * Creates a new instance of {@link AmazonS3Client} and returns it. If the provided access key id and secret access
    * key are both defined, it will be used for the authentication otherwise it will rely in the default credential
    * provider chain
    */
   private AmazonS3 createAmazonS3(ClientConfiguration config, String accessKeyId, String secretAccessKey)
      throws RepositoryConfigurationException
   {
      try
      {
         if (accessKeyId != null && !accessKeyId.isEmpty() && secretAccessKey != null && !secretAccessKey.isEmpty())
         {
            LOG.debug("The access key id and the secret access key have been configured so it will use it to connect to AS3");
            return new AmazonS3Client(new BasicAWSCredentials(accessKeyId, secretAccessKey), config);
         }
         LOG.debug("No access key id and the secret access key have been configured so it will rely on the default credential "
            + "provider chain to connect to AS3");
         return new AmazonS3Client(config);
      }
      catch (Exception e)
      {
         throw new RepositoryConfigurationException("Could not instantiate the AS3 client", e);
      }
   }

   /**
    * Creates a new instance of {@link ClientConfiguration} according to what could be find in the provided properties
    */
   private ClientConfiguration createClientConfiguration(Properties props) throws RepositoryConfigurationException
   {
      try
      {
         ClientConfiguration config = new ClientConfiguration();
         String value = props.getProperty(CONNECTION_TIMEOUT);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", CONNECTION_TIMEOUT, value);
            config.setConnectionTimeout(Integer.valueOf(value));
         }
         value = props.getProperty(MAX_CONNECTIONS);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", MAX_CONNECTIONS, value);
            config.setMaxConnections(Integer.valueOf(value));
         }
         value = props.getProperty(MAX_ERROR_RETRY);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", MAX_ERROR_RETRY, value);
            config.setMaxErrorRetry(Integer.valueOf(value));
         }
         value = props.getProperty(SOCKET_TIMEOUT);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", SOCKET_TIMEOUT, value);
            config.setSocketTimeout(Integer.valueOf(value));
         }
         value = props.getProperty(SOCKET_SEND_BUFFER_SIZE_HINT);
         int socketSendBufferSizeHint = 0;
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", SOCKET_SEND_BUFFER_SIZE_HINT, value);
            socketSendBufferSizeHint = Integer.valueOf(value);
         }
         value = props.getProperty(SOCKET_RECEIVE_BUFFER_SIZE_HINT);
         int socketReceiveBufferSizeHint = 0;
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", SOCKET_RECEIVE_BUFFER_SIZE_HINT, value);
            socketReceiveBufferSizeHint = Integer.valueOf(value);
         }
         config.setSocketBufferSizeHints(socketSendBufferSizeHint, socketReceiveBufferSizeHint);
         value = props.getProperty(LOCAL_ADDRESS);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", LOCAL_ADDRESS, value);
            config.setLocalAddress(InetAddress.getByName(value));
         }
         value = props.getProperty(PROTOCOL);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", PROTOCOL, value);
            config.setProtocol(value.toLowerCase().equals(Protocol.HTTP.toString()) ? Protocol.HTTP : Protocol.HTTPS);
         }
         value = props.getProperty(SIGNER_OVERRIDE);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", SIGNER_OVERRIDE, value);
            config.setSignerOverride(value);
         }
         value = props.getProperty(USER_AGENT);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", USER_AGENT, value);
            config.setUserAgent(value);
         }
         value = props.getProperty(PROXY_HOST);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", PROXY_HOST, value);
            config.setProxyHost(value);
         }
         value = props.getProperty(PROXY_PORT);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", PROXY_PORT, value);
            config.setProxyPort(Integer.valueOf(value));
         }
         value = props.getProperty(PROXY_DOMAIN);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", PROXY_DOMAIN, value);
            config.setProxyDomain(value);
         }
         value = props.getProperty(PROXY_USERNAME);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set", PROXY_USERNAME);
            config.setProxyUsername(value);
         }
         value = props.getProperty(PROXY_PASSWORD);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set", PROXY_PASSWORD);
            config.setProxyPassword(value);
         }
         value = props.getProperty(PROXY_WORKSTATION);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", PROXY_WORKSTATION, value);
            config.setProxyWorkstation(value);
         }
         value = props.getProperty(PREEMPTIVE_BASIC_PROXY_AUTH);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", PREEMPTIVE_BASIC_PROXY_AUTH, value);
            config.setPreemptiveBasicProxyAuth(Boolean.valueOf(value));
         }
         return config;
      }
      catch (Exception e)
      {
         throw new RepositoryConfigurationException("Could not instantiate the client configuration", e);
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

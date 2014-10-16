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
package org.exoplatform.connectors.s3.impl.adapter;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;

import org.exoplatform.services.jcr.config.RepositoryConfigurationException;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.naming.InitialContextInitializer;

import java.net.InetAddress;
import java.util.Hashtable;
import java.util.Properties;

import javax.naming.Context;
import javax.naming.Name;
import javax.naming.RefAddr;
import javax.naming.Reference;
import javax.naming.spi.ObjectFactory;

/**
 * This implementation of {@link ObjectFactory} provides instances of {@link AmazonS3}.
 * This factory is aimed to be used in standalone mode by the component {@link InitialContextInitializer}
 * or by Application Server that only supports JCA 1.0 like Tomcat.
 * 
 * @author <a href="mailto:nfilotto@exoplatform.com">Nicolas Filotto</a>
 * @version $Id$
 *
 */
public class AmazonS3ObjectFactory implements ObjectFactory
{
   private static final Log LOG = ExoLogger.getLogger("exo.jcr.component.core.AmazonS3ObjectFactory");

   /**
    * The amount of time to wait (in milliseconds) when initially
    * establishing a connection before giving up and timing out. A value of 0
    * means infinity, and is not recommended.
    */
   private static final String CONNECTION_TIMEOUT = "connection-timeout";

   /**
    * The maximum number of allowed open HTTP connections.
    */
   private static final String MAX_CONNECTIONS = "max-connections";

   /**
    * The expiration time for a connection in the connection pool. When a
    * connection is retrieved from the connection pool, this parameter is
    * checked to see if the connection can be reused.
    */
   private static final String CONNECTION_TTL = "connection-ttl";

   /**
    * The maximum number of retry attempts for failed retryable requests
    * (ex: 5xx error responses from services).
    */
   private static final String MAX_ERROR_RETRY = "max-error-retry";

   /**
    * The amount of time to wait (in milliseconds) for data to be
    * transfered over an established, open connection before the connection
    * times out and is closed. A value of 0 means infinity, and isn't recommended.
    */
   private static final String SOCKET_TIMEOUT = "socket-timeout";

   /**
    *  The optional size hints (in bytes) for the low level TCP send and
    * receive buffers. This is an advanced option for advanced users who want
    * to tune low level TCP parameters to try and squeeze out more performance.
    * <p>
    * The optimal TCP buffer sizes for a particular application are highly
    * dependent on network configuration and operating system configuration and
    * capabilities. For example, most modern operating systems provide
    * auto-tuning functionality for TCP buffer sizes, which can have a big
    * impact on performance for TCP connections that are held open long enough
    * for the auto-tuning to optimize buffer sizes.
    * <p>
    * Large buffer sizes (ex: 2MB) will allow the operating system to buffer
    * more data in memory without requiring the remote server to acknowledge
    * receipt of that information, so can be particularly useful when the
    * network has high latency.
    * <p>
    * This is only a <b>hint</b>, and the operating system may choose not to
    * honor it. When using this option, users should <b>always</b> check the
    * operating system's configured limits and defaults. Most OS's have a
    * maximum TCP buffer size limit configured, and won't let you go beyond
    * that limit unless you explicitly raise the max TCP buffer size limit.
    * <p>
    * There are many resources available online to help with configuring TCP
    * buffer sizes and operating system specific TCP settings, including:
    * <ul>
    * <li>http://onlamp.com/pub/a/onlamp/2005/11/17/tcp_tuning.html</li>
    * <li>http://fasterdata.es.net/TCP-tuning/</li>
    * </ul>
    * {@link AmazonS3ObjectFactory#SOCKET_SEND_BUFFER_SIZE_HINT}
    * {@link AmazonS3ObjectFactory#SOCKET_RECEIVE_BUFFER_SIZE_HINT}
    */
   private static final String SOCKET_SEND_BUFFER_SIZE_HINT = "socket-send-buffer-size-hint";
   private static final String SOCKET_RECEIVE_BUFFER_SIZE_HINT = "socket-receive-buffer-size-hint";

   /**
    * The optional local address the client will bind to.
    */
   private static final String LOCAL_ADDRESS = "local-address";

   /**
    * The protocol (i.e. HTTP or HTTPS) to use when connecting to Amazon
    * Web Services.
    * <p>
    * The default configuration is to use HTTPS for all requests for increased
    * security.
    * <p>
    */
   private static final String PROTOCOL = "protocol";

   /**
    * The name of the signature algorithm to use for signing requests
    * made by this client. If not set or explicitly set to null, the client
    * will choose a signature algorithm to use based on a configuration file
    * of supported signature algorithms for the service and region.
    * <p>
    * Most users do not need to concern themselves with which signature
    * algorithm is being used, as the defaults will be sufficient. This
    * setting exists only so advanced users can opt in to newer signature
    * protocols which have not yet been made the default for a particular
    * service/region.
    * <p>
    * Not all services support all signature algorithms, and configuring an
    * unsupported signature algorithm will lead to authentication failures.
    * Use me at your own risk, and only after consulting the documentation
    * for the service to ensure it actually does supports your chosen
    * algorithm.
    * <p>
    * If non-null, the name returned from this method is used to look up
    * a {@code Signer} class implementing the chosen algorithm by the
    * {@code com.amazonaws.auth.SignerFactory} class.
    */
   private static final String SIGNER_OVERRIDE = "signer-override";

   /**
    * The HTTP user agent header to send with all requests.
    */
   private static final String USER_AGENT = "user-agent";

   /**
    * The optional proxy host the client will connect through.
    */
   private static final String PROXY_HOST = "proxy-host";

   /**
    * The optional proxy port the client will connect through.
    */
   private static final String PROXY_PORT = "proxy-port";

   /**
    * The optional Windows domain name for configuration an NTLM proxy.
    * If you aren't using a Windows NTLM proxy, you do not need to set this
    * field.
    */
   private static final String PROXY_DOMAIN = "proxy-domain";

   /**
    * The optional proxy user name to use if connecting through a proxy.
    */
   private static final String PROXY_USERNAME = "proxy-username";

   /**
    * The optional proxy password to use when connecting through a proxy.
    */
   private static final String PROXY_PASSWORD = "proxy-password";

   /**
    * The optional Windows workstation name for configuring NTLM proxy
    * support. If you aren't using a Windows NTLM proxy, you do not need to set
    * this field.
    */
   private static final String PROXY_WORKSTATION = "proxy-workstation";

   /**
    * Indicates whether to attempt to authenticate preemptively against proxy servers
    * using basic authentication
    */
   private static final String PREEMPTIVE_BASIC_PROXY_AUTH = "preemptive-basic-proxy-auth";

   /**
    * Indicates whether to use gzip compression when making HTTP requests.
    */
   private static final String USE_GZIP = "use-gzip";

   /**
    * The AWS access key.
    */
   private static final String ACCESS_KEY_ID = "access-key-id";

   /**
    * The AWS secret access key.
    */
   private static final String SECRET_KEY = "secret-access-key";

   /**
    * The name of the region. It is an optional parameter.
    */
   private static final String REGION = "region";

   /**
    * {@inheritDoc}
    */
   public Object getObjectInstance(Object obj, Name name, Context nameCtx, Hashtable<?, ?> environment)
      throws Exception
   {
      if ((obj == null) || !(obj instanceof Reference))
      {
         return null;
      }

      Reference ref = (Reference)obj;
      if (!"com.amazonaws.services.s3.AmazonS3".equals(ref.getClassName()))
      {
         return null;
      }
      Properties properties = new Properties();
      for (int i = 0; i < ref.size(); i++)
      {
         String propertyName = ref.get(i).getType();
         RefAddr ra = ref.get(propertyName);
         if (ra != null)
         {
            String propertyValue = ra.getContent().toString();
            properties.setProperty(propertyName, propertyValue);
         }
      }
      String accessKeyId = properties.getProperty(ACCESS_KEY_ID);
      String secretAccessKey = properties.getProperty(SECRET_KEY);
      ClientConfiguration config = createClientConfiguration(properties);
      AmazonS3 as3 = createAmazonS3(config, accessKeyId, secretAccessKey);
      String region = properties.getProperty(REGION);
      if (region != null && !region.isEmpty())
      {
         LOG.debug("The parameter {} has been set to {}", REGION, region);
         setRegion(as3, region);
      }
      return as3;
   }

   /**
    * Sets the region to use for all the accesses.
    */
   private void setRegion(AmazonS3 as3, String region) throws RepositoryConfigurationException
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
         value = props.getProperty(CONNECTION_TTL);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", CONNECTION_TTL, value);
            config.setConnectionTTL(Long.valueOf(value));
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
         value = props.getProperty(USE_GZIP);
         if (value != null && !value.isEmpty())
         {
            LOG.debug("The parameter {} has been set to {}", USE_GZIP, value);
            config.setUseGzip(Boolean.valueOf(value));
         }
         return config;
      }
      catch (Exception e)
      {
         throw new RepositoryConfigurationException("Could not instantiate the client configuration", e);
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
}

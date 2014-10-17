/*
 * Copyright (C) 2003-2014 eXo Platform SAS.
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
package com.amazonaws.http;

import com.amazonaws.ClientConfiguration;

/**
 * Created by The eXo Platform SAS
 * 
 * @author <a href="mailto:pnedonosko@exoplatform.com">Peter Nedonosko</a>
 * @version $Id: PoolableAmazonHttpClient.java 00000 Oct 16, 2014 pnedonosko $
 * 
 */
public class PoolableAmazonHttpClient extends AmazonHttpClient
{
   /**
    * @param config
    * @param httpClient
    * @param requestMetricCollector
    */
   public PoolableAmazonHttpClient(ClientConfiguration config, long poolTimeout)
   {
      super(config, new PoolableHttpClientFactory(poolTimeout).createHttpClient(config), null);
   }
}

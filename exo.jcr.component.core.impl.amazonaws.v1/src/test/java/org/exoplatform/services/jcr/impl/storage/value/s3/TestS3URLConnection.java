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

import org.exoplatform.connectors.s3.impl.adapter.AmazonS3ObjectFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Properties;

import javax.naming.Reference;
import javax.naming.StringRefAddr;

/**
 * Tests related to the class {@link S3URLConnection}.
 * 
 * @author <a href="mailto:foo@bar.org">Foo Bar</a>
 * @version $Id$
 *
 */
public class TestS3URLConnection
{
   private static final String CONTENT = "This is the content of my value for the test TestS3URLConnection!";

   private String bucket;
   private AmazonS3 as3;
   private S3URLConnection url;

   @Before
   public void init() throws Exception
   {
      AmazonS3ObjectFactory factory = new AmazonS3ObjectFactory();
      Reference ref = new Reference("com.amazonaws.services.s3.AmazonS3");
      Properties properties = new Properties();
      properties.load(TestS3ValueUtil.class.getResourceAsStream("/conf/standalone/default.properties"));
      this.bucket = properties.getProperty("bucket");
      for (Map.Entry<Object, Object> entry : properties.entrySet())
      {
         ref.add(new StringRefAddr((String)entry.getKey(), (String)entry.getValue()));
      }
      this.as3 = (AmazonS3)factory.getObjectInstance(ref, null, null, null);
      this.url = new S3URLConnection(as3, bucket, "foo", null);
      S3ValueUtil.writeValue(as3, bucket, url.getIdResource(), new ByteArrayInputStream(CONTENT.getBytes("UTF-8")));
   }

   @After
   public void destroy() throws Exception
   {
      if (url != null)
         S3ValueUtil.delete(as3, bucket, url.getIdResource());
   }

   @Test
   public void testGetContentLength() throws Exception
   {
      Assert.assertEquals(CONTENT.length(), url.getContentLength());
   }


   @Test
   public void testGetInputStream() throws Exception
   {
      Assert.assertEquals(CONTENT.length(), url.getInputStream().available());
      InputStream io = url.getInputStream();
      byte[] buffer = new byte[128];
      int l = io.read(buffer);
      Assert.assertEquals(-1, io.read(buffer));
      Assert.assertEquals(0, io.available());
      io.close();
      Assert.assertEquals(CONTENT, new String(buffer, 0, l, "UTF-8"));
      io = url.getInputStream();
      buffer = new byte[128];
      for (int i = 0; i < l; i++)
      {
         int r = io.read();
         Assert.assertFalse(r == -1);
         Assert.assertEquals(l - i - 1, io.available());
         buffer[i] = (byte)r;
      }
      Assert.assertEquals(-1, io.read());
      Assert.assertEquals(0, io.available());
      io.close();
      Assert.assertEquals(CONTENT, new String(buffer, 0, l, "UTF-8"));
      io = url.getInputStream();
      buffer = new byte[13];
      int remaining = CONTENT.length();
      Assert.assertEquals(remaining, io.available());
      Assert.assertEquals(13, io.read(buffer));
      Assert.assertEquals(remaining -= 13, io.available());
      Assert.assertEquals(13, io.read(buffer));
      Assert.assertEquals(remaining -= 13, io.available());
      Assert.assertEquals(13, io.read(buffer));
      Assert.assertEquals(remaining -= 13, io.available());
      Assert.assertEquals(13, io.read(buffer));
      Assert.assertEquals(remaining -= 13, io.available());
      Assert.assertEquals(13, io.read(buffer));
      Assert.assertEquals(0, io.available());
      Assert.assertEquals(-1, io.read(buffer));
      io.close();

      io = url.getInputStream();
      Assert.assertEquals(CONTENT.length(), io.available());
      io.skip(13);
      Assert.assertEquals(CONTENT.length() - 13, io.available());
      buffer = new byte[128];
      int l2 = io.read(buffer);
      Assert.assertEquals(-1, io.read(buffer));
      Assert.assertEquals(0, io.available());
      io.close();
      Assert.assertEquals(CONTENT.substring(13), new String(buffer, 0, l2, "UTF-8"));
      io = url.getInputStream();
      io.skip(13);
      buffer = new byte[128];
      for (int i = 0; i < l2; i++)
      {
         int r = io.read();
         Assert.assertFalse(r == -1);
         Assert.assertEquals(l2 - i - 1, io.available());
         buffer[i] = (byte)r;
      }
      Assert.assertEquals(-1, io.read());
      Assert.assertEquals(0, io.available());
      io.close();
      Assert.assertEquals(CONTENT.substring(13), new String(buffer, 0, l2, "UTF-8"));
      io = url.getInputStream();
      buffer = new byte[13];
      remaining = CONTENT.length();
      Assert.assertEquals(remaining, io.available());
      io.skip(13);
      Assert.assertEquals(remaining -= 13, io.available());
      Assert.assertEquals(13, io.read(buffer));
      Assert.assertEquals(remaining -= 13, io.available());
      Assert.assertEquals(13, io.read(buffer));
      Assert.assertEquals(remaining -= 13, io.available());
      Assert.assertEquals(13, io.read(buffer));
      Assert.assertEquals(remaining -= 13, io.available());
      Assert.assertEquals(13, io.read(buffer));
      Assert.assertEquals(0, io.available());
      Assert.assertEquals(-1, io.read(buffer));
      io.close();
   }
}

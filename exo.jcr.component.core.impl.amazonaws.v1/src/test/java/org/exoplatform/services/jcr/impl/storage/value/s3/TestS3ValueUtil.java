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
import org.exoplatform.services.jcr.impl.dataflow.SpoolConfig;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.jcr.PropertyType;
import javax.naming.Reference;
import javax.naming.StringRefAddr;

/**
 * Tests related to the class {@link S3ValueUtil}.
 * 
 * @author <a href="mailto:nfilotto@exoplatform.com">Nicolas Filotto</a>
 * @version $Id$
 *
 */
public class TestS3ValueUtil
{
   private String bucket;
   private AmazonS3 as3;

   @Before
   public void createAmazonS3() throws Exception
   {
      AmazonS3ObjectFactory factory = new AmazonS3ObjectFactory();
      Reference ref = new Reference("com.amazonaws.services.s3.AmazonS3");
      Properties properties = new Properties();
      properties.load(TestS3ValueUtil.class.getResourceAsStream("/conf/standalone/default.properties"));
      this.bucket = properties.getProperty("bucket");
      for (Map.Entry<Object, Object> entry : properties.entrySet())
      {
         if (entry.getKey().equals("bucket") || entry.getKey().equals("max-connections"))
            continue;
         ref.add(new StringRefAddr((String)entry.getKey(), (String)entry.getValue()));
      }
      ref.add(new StringRefAddr("max-connections", "1"));
      this.as3 = (AmazonS3)factory.getObjectInstance(ref, null, null, null);
   }

   @Test
   public void testAll() throws Exception
   {
      Assert.assertNotNull(bucket);
      Assert.assertNotNull(as3);
      int total = 12;
      String content = "This is the content of my value !";
      long length = content.length();
      for (int i = 0; i < total; i++)
      {
         S3ValueUtil.writeValue(as3, bucket, "foo" + i, new ByteArrayInputStream(content.getBytes("UTF-8")));
      }
      SpoolConfig config = SpoolConfig.getDefaultSpoolConfig();
      for (int i = 0; i < total; i++)
      {
         // length <= spoolConfig.maxBufferSize without trying to access to the content 
         S3ValueUtil.readValueData(as3, bucket, "foo" + i, PropertyType.BINARY, 1, config);
      }
      for (int i = 0; i < total; i++)
      {
         // length <= spoolConfig.maxBufferSize, access to the content without reading it using getAsStream
         S3ValueUtil.readValueData(as3, bucket, "foo" + i, PropertyType.BINARY, 1, config).getAsStream();
      }
      for (int i = 0; i < total; i++)
      {
         // length <= spoolConfig.maxBufferSize, access to the content without reading it using getAsByteArray
         S3ValueUtil.readValueData(as3, bucket, "foo" + i, PropertyType.BINARY, 1, config).getAsByteArray();
      }
      for (int i = 0; i < total; i++)
      {
         // length <= spoolConfig.maxBufferSize, access to the content without reading it
         S3ValueUtil.getContent(as3, bucket, "foo" + i, config);
      }
      for (int i = 0; i < total; i++)
      {
         // length <= spoolConfig.maxBufferSize, access to the content and read it using getAsStream
         InputStream io = S3ValueUtil.readValueData(as3, bucket, "foo" + i, PropertyType.BINARY, 1, config).getAsStream();
         byte[] buffer = new byte[128];
         int l = io.read(buffer);
         Assert.assertEquals(-1, io.read());
         io.close();
         Assert.assertEquals(content, new String(buffer, 0, l, "UTF-8"));
      }
      for (int i = 0; i < total; i++)
      {
         // length <= spoolConfig.maxBufferSize, access to the content and read it using getAsByteArray
         byte[] result = S3ValueUtil.readValueData(as3, bucket, "foo" + i, PropertyType.BINARY, 1, config).getAsByteArray();
         Assert.assertEquals(content, new String(result, "UTF-8"));
      }
      for (int i = 0; i < total; i++)
      {
         // length <= spoolConfig.maxBufferSize, access to the content and read it
         InputStream io = S3ValueUtil.getContent(as3, bucket, "foo" + i, config);
         byte[] buffer = new byte[128];
         int l = io.read(buffer);
         Assert.assertEquals(-1, io.read());
         io.close();
         Assert.assertEquals(content, new String(buffer, 0, l, "UTF-8"));
      }
      for (int i = 0; i < total; i++)
      {
         // length <= spoolConfig.maxBufferSize, access to the content and read it using getAsStream without closing the stream
         InputStream io = S3ValueUtil.readValueData(as3, bucket, "foo" + i, PropertyType.BINARY, 1, config).getAsStream();
         byte[] buffer = new byte[128];
         int l = io.read(buffer);
         Assert.assertEquals(-1, io.read());
         Assert.assertEquals(content, new String(buffer, 0, l, "UTF-8"));
      }
      for (int i = 0; i < total; i++)
      {
         // length <= spoolConfig.maxBufferSize, access to the content and read it without closing the stream
         InputStream io = S3ValueUtil.getContent(as3, bucket, "foo" + i, config);
         byte[] buffer = new byte[128];
         int l = io.read(buffer);
         Assert.assertEquals(-1, io.read());
         Assert.assertEquals(content, new String(buffer, 0, l, "UTF-8"));
      }
      config = SpoolConfig.getDefaultSpoolConfig();
      config.maxBufferSize = 16;
      for (int i = 0; i < total; i++)
      {
         // length > spoolConfig.maxBufferSize without trying to access to the content 
         S3ValueUtil.readValueData(as3, bucket, "foo" + i, PropertyType.BINARY, 1, config);
      }
      for (int i = 0; i < total; i++)
      {
         // length > spoolConfig.maxBufferSize, access to the content without reading it using getAsStream
         S3ValueUtil.readValueData(as3, bucket, "foo" + i, PropertyType.BINARY, 1, config).getAsStream();
      }
      for (int i = 0; i < total; i++)
      {
         // length > spoolConfig.maxBufferSize, access to the content without reading it using getAsByteArray
         S3ValueUtil.readValueData(as3, bucket, "foo" + i, PropertyType.BINARY, 1, config).getAsByteArray();
      }
      for (int i = 0; i < total; i++)
      {
         // length > spoolConfig.maxBufferSize, access to the content without reading it
         S3ValueUtil.getContent(as3, bucket, "foo" + i, config);
      }
      for (int i = 0; i < total; i++)
      {
         // length > spoolConfig.maxBufferSize, access to the content and read it using getAsStream
         InputStream io = S3ValueUtil.readValueData(as3, bucket, "foo" + i, PropertyType.BINARY, 1, config).getAsStream();
         byte[] buffer = new byte[128];
         int l = io.read(buffer);
         Assert.assertEquals(-1, io.read());
         io.close();
         Assert.assertEquals(content, new String(buffer, 0, l, "UTF-8"));
      }
      for (int i = 0; i < total; i++)
      {
         // length > spoolConfig.maxBufferSize, access to the content and read it using getAsByteArray
         byte[] result = S3ValueUtil.readValueData(as3, bucket, "foo" + i, PropertyType.BINARY, 1, config).getAsByteArray();
         Assert.assertEquals(content, new String(result, "UTF-8"));
      }
      for (int i = 0; i < total; i++)
      {
         // length > spoolConfig.maxBufferSize, access to the content and read it
         InputStream io = S3ValueUtil.getContent(as3, bucket, "foo" + i, config);
         byte[] buffer = new byte[128];
         int l = io.read(buffer);
         Assert.assertEquals(-1, io.read());
         io.close();
         Assert.assertEquals(content, new String(buffer, 0, l, "UTF-8"));
      }
      for (int i = 0; i < total; i++)
      {
         // length > spoolConfig.maxBufferSize, access to the content and read it using getAsStream without closing the stream
         InputStream io = S3ValueUtil.readValueData(as3, bucket, "foo" + i, PropertyType.BINARY, 1, config).getAsStream();
         byte[] buffer = new byte[128];
         int l = io.read(buffer);
         Assert.assertEquals(-1, io.read());
         Assert.assertEquals(content, new String(buffer, 0, l, "UTF-8"));
      }
      for (int i = 0; i < total; i++)
      {
         // length > spoolConfig.maxBufferSize, access to the content and read it without closing the stream
         InputStream io = S3ValueUtil.getContent(as3, bucket, "foo" + i, config);
         byte[] buffer = new byte[128];
         int l = io.read(buffer);
         Assert.assertEquals(-1, io.read());
         Assert.assertEquals(content, new String(buffer, 0, l, "UTF-8"));
      }
      for (int i = 0; i < total; i++)
      {
         Assert.assertTrue(S3ValueUtil.exists(as3, bucket, "foo" + i));
      }
      for (int i = 0; i < total; i++)
      {
         Assert.assertEquals(length, S3ValueUtil.getContentLength(as3, bucket, "foo" + i));
      }
      for (int i = 0; i < total; i++)
      {
         S3ValueUtil.copy(as3, bucket, "foo" + i, "foo/" + i);
      }
      for (int i = 0; i < total; i++)
      {
         Assert.assertTrue(S3ValueUtil.exists(as3, bucket, "foo/" + i));
      }
      for (int i = 0; i < total; i++)
      {
         Assert.assertEquals(length, S3ValueUtil.getContentLength(as3, bucket, "foo/" + i));
      }
      for (int i = 0; i < total; i++)
      {
         Assert.assertTrue(S3ValueUtil.delete(as3, bucket, "foo" + i));
      }
      for (int i = 0; i < total; i++)
      {
         Assert.assertFalse(S3ValueUtil.exists(as3, bucket, "foo" + i));
      }
      for (int i = 0; i < total; i++)
      {
         Assert.assertEquals(-1, S3ValueUtil.getContentLength(as3, bucket, "foo" + i));
      }
      for (int i = 0; i < total; i++)
      {
         Assert.assertTrue(S3ValueUtil.exists(as3, bucket, "foo/" + i));
      }
      for (int i = 0; i < total; i++)
      {
         Assert.assertEquals(length, S3ValueUtil.getContentLength(as3, bucket, "foo/" + i));
      }
      for (int i = 0; i < total; i++)
      {
         List<String> keys = S3ValueUtil.getKeys(as3, bucket, "bar/");
         Assert.assertEquals(0, keys.size());
      }
      for (int i = 0; i < total; i++)
      {
         List<String> keys = S3ValueUtil.getKeys(as3, bucket, "foo/");
         Assert.assertEquals(total, keys.size());
         for (int j = 0; j < total; j++)
         {
            keys.remove("foo/" + j);
         }
         Assert.assertEquals(0, keys.size());
      }
      String key1 = null, key2 = null;
      for (int i = 0; i < total; i++)
      {
         if (key1 == null)
         {
            key1 = "foo/" + i;
         }
         else if (key2 == null)
         {
            key2 = "foo/" + i;
         }
         if (key1 != null && key2 != null)
         {
            List<String> result = S3ValueUtil.delete(as3, bucket, key1, key2);
            Assert.assertNull(result);
            key1 = null;
            key2 = null;
         }
      }
      for (int i = 0; i < total; i++)
      {
         Assert.assertFalse(S3ValueUtil.exists(as3, bucket, "foo/" + i));
      }
      for (int i = 0; i < total; i++)
      {
         Assert.assertEquals(-1, S3ValueUtil.getContentLength(as3, bucket, "foo/" + i));
      }
      key1 = null;
      key2 = null;
      for (int i = 0; i < total; i++)
      {
         if (key1 == null)
         {
            key1 = "foo/" + i;
         }
         else if (key2 == null)
         {
            key2 = "foo/" + i;
         }
         if (key1 != null && key2 != null)
         {
            List<String> result = S3ValueUtil.delete(as3, bucket, key1, key2);
            Assert.assertNull(result);
            key1 = null;
            key2 = null;
         }
      }
   }
}

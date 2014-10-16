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
import org.exoplatform.services.jcr.datamodel.ValueData;
import org.exoplatform.services.jcr.impl.dataflow.SpoolConfig;
import org.exoplatform.services.jcr.impl.dataflow.persistent.ByteArrayPersistedValueData;
import org.exoplatform.services.jcr.impl.storage.value.ValueDataNotFoundException;
import org.exoplatform.services.jcr.impl.util.io.FileCleaner;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import javax.jcr.PropertyType;
import javax.naming.Reference;
import javax.naming.StringRefAddr;

/**
 * Tests related to the class {@link S3ValueIOChannel}.
 * 
 * @author <a href="mailto:nfilotto@exoplatform.com">Nicolas Filotto</a>
 * @version $Id$
 *
 */
public class TestS3ValueIOChannel
{
   private static final String CONTENT = "This is the content of my value for the test S3ValueIOChannel!";

   private String bucket;
   private AmazonS3 as3;
   private S3ValueIOChannel channel;
   private FileCleaner cleaner;

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
      this.cleaner = new FileCleaner();
      S3ValueStorage storage = new S3ValueStorage(as3, bucket, "TestS3ValueStorage", cleaner);
      this.channel = (S3ValueIOChannel)storage.openIOChannel();
   }

   @After
   public void destroy() throws Exception
   {
      if (channel != null)
         channel.close();
      if (cleaner != null)
         cleaner.halt();
   }

   @Test
   public void testAll() throws Exception
   {
      SpoolConfig config = SpoolConfig.getDefaultSpoolConfig();
      String propertyId = "verylongbar";
      try
      {
         channel.read(propertyId, 1, PropertyType.BINARY, config);
         Assert.fail("A IOException should be thrown as the value doesn't exist yet");
      }
      catch (IOException e)
      {
         // expected
      }
      ValueData value = new ByteArrayPersistedValueData(1, CONTENT.getBytes("UTF-8"));
      channel.write(propertyId, value);
      try
      {
         channel.read(propertyId, 1, PropertyType.BINARY, config);
         Assert.fail("A IOException should be thrown as the value has not been committed so far");
      }
      catch (IOException e)
      {
         // expected
      }
      channel.prepare();
      channel.read(propertyId, 1, PropertyType.BINARY, config);
      channel.twoPhaseCommit();
      value = channel.read(propertyId, 1, PropertyType.BINARY, config);
      Assert.assertNotNull(value);
      Assert.assertEquals(CONTENT, new String(value.getAsByteArray(), "UTF-8"));
      channel.delete(propertyId);
      // Ensure that the content is still accessible as the delete has not been committed so far
      value = channel.read(propertyId, 1, PropertyType.BINARY, config);
      Assert.assertNotNull(value);
      Assert.assertEquals(CONTENT, new String(value.getAsByteArray(), "UTF-8"));
      channel.prepare();
      try
      {
         channel.read(propertyId, 1, PropertyType.BINARY, config);
         Assert.fail("A IOException should be thrown as the value has not been committed so far");
      }
      catch (IOException e)
      {
         // expected
      }
      channel.twoPhaseCommit();
      try
      {
         channel.read(propertyId, 1, PropertyType.BINARY, config);
         Assert.fail("A IOException should be thrown as the value has not been committed so far");
      }
      catch (IOException e)
      {
         // expected
      }
      value = new ByteArrayPersistedValueData(1, CONTENT.getBytes("UTF-8"));
      channel.write(propertyId, value);
      try
      {
         channel.read(propertyId, 1, PropertyType.BINARY, config);
         Assert.fail("A IOException should be thrown as the value has not been committed so far");
      }
      catch (IOException e)
      {
         // expected
      }
      channel.prepare();
      channel.read(propertyId, 1, PropertyType.BINARY, config);
      channel.rollback();
      // Ensure that the content is still not accessible after the roll-back
      try
      {
         channel.read(propertyId, 1, PropertyType.BINARY, config);
         Assert.fail("A IOException should be thrown as the value has not been committed so far");
      }
      catch (IOException e)
      {
         // expected
      }
      channel.write(propertyId, value);
      channel.commit();
      value = channel.read(propertyId, 1, PropertyType.BINARY, config);
      Assert.assertNotNull(value);
      Assert.assertEquals(CONTENT, new String(value.getAsByteArray(), "UTF-8"));
      channel.delete(propertyId);
      // Ensure that the content is still accessible as the delete has not been committed so far
      value = channel.read(propertyId, 1, PropertyType.BINARY, config);
      Assert.assertNotNull(value);
      Assert.assertEquals(CONTENT, new String(value.getAsByteArray(), "UTF-8"));
      channel.prepare();
      try
      {
         channel.read(propertyId, 1, PropertyType.BINARY, config);
         Assert.fail("A IOException should be thrown as the value has not been committed so far");
      }
      catch (IOException e)
      {
         // expected
      }
      channel.rollback();
      // Ensure that the content is still accessible after the roll-back
      value = channel.read(propertyId, 1, PropertyType.BINARY, config);
      Assert.assertNotNull(value);
      Assert.assertEquals(CONTENT, new String(value.getAsByteArray(), "UTF-8"));
      channel.delete(propertyId);
      channel.commit();
      // Ensure that the content is not accessible after the commit
      try
      {
         channel.read(propertyId, 1, PropertyType.BINARY, config);
         Assert.fail("A IOException should be thrown as the value has not been committed so far");
      }
      catch (IOException e)
      {
         // expected
      }
      propertyId = "superlongfoo";
      try
      {
         channel.checkValueData(propertyId, 1);
         Assert.fail("A ValueDataNotFoundException should be thrown as the value doesn't exist yet");
      }
      catch (ValueDataNotFoundException e)
      {
         // expected
      }
      channel.repairValueData(propertyId, 1);
      value = channel.read(propertyId, 1, PropertyType.BINARY, config);
      Assert.assertNotNull(value);
      Assert.assertEquals(0, value.getLength());
      Assert.assertEquals(0, value.getAsByteArray().length);
      // Ensure that it doesn't fail anymore
      channel.checkValueData(propertyId, 1);
      channel.delete(propertyId);
      // Ensure that it doesn't fail as the delete has not been committed so far
      channel.checkValueData(propertyId, 1);
      channel.prepare();
      try
      {
         channel.checkValueData(propertyId, 1);
         Assert.fail("A ValueDataNotFoundException should be thrown");
      }
      catch (ValueDataNotFoundException e)
      {
         // expected
      }
      channel.twoPhaseCommit();
      try
      {
         channel.checkValueData(propertyId, 1);
         Assert.fail("A ValueDataNotFoundException should be thrown");
      }
      catch (ValueDataNotFoundException e)
      {
         // expected
      }
   }
}

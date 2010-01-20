/*
 * Copyright (C) 2009 eXo Platform SAS.
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
package org.exoplatform.services.jcr.access;

import org.exoplatform.services.security.MembershipEntry;

/**
 * Created by The eXo Platform SAS.
 * 
 * @author Gennady Azarenkov
 * @version $Id: AccessControlEntry.java 14464 2008-05-19 11:05:20Z pnedonosko $
 */
public class AccessControlEntry
{

   private final String identity;

   private final String permission;

   private volatile MembershipEntry membership;

   public static final String DELIMITER = " ";

   public AccessControlEntry(String identity, String permission)
   {
      this.identity = identity;
      this.permission = permission;
   }

   public String getIdentity()
   {
      return identity;
   }

   public String getPermission()
   {
      return permission;
   }

   public MembershipEntry getMembershipEntry()
   {
      if (membership == null)
      {
         synchronized (this)
         {
            if (membership == null)
            {
               membership = MembershipEntry.parse(getIdentity());
            }
         }
      }
      return membership;
   }
   
   public String getAsString()
   {
      return identity + AccessControlEntry.DELIMITER + permission;
   }

   public boolean equals(Object obj)
   {
      if (obj == this)
         return true;
      if (obj instanceof AccessControlEntry)
      {
         AccessControlEntry another = (AccessControlEntry)obj;
         return getAsString().equals(another.getAsString());
      }
      return false;
   }

   @Override
   public String toString()
   {
      return super.toString() + " (" + getAsString() + ")";
   }

}

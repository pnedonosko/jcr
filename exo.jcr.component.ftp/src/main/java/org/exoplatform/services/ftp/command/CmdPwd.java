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
package org.exoplatform.services.ftp.command;

import org.exoplatform.services.ftp.FtpConst;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Created by The eXo Platform SAS Author : Vitaly Guly <gavrik-vetal@ukr.net/mail.ru>
 * 
 * @version $Id: $
 */

public class CmdPwd extends FtpCommandImpl
{

   public CmdPwd()
   {
      commandName = FtpConst.Commands.CMD_PWD;
   }

   public void run(String[] params) throws IOException
   {
      ArrayList<String> curPath = clientSession().getPath();

      String path = "/";
      for (int i = 0; i < curPath.size(); i++)
      {
         path += curPath.get(i);
         if (i != (curPath.size() - 1))
         {
            path += "/";
         }
      }

      reply(String.format(FtpConst.Replyes.REPLY_257, path));
   }

}
/*
 * Copyright (C) 2003-2010 eXo Platform SAS.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, see<http://www.gnu.org/licenses/>.
 */
package org.exoplatform.services.jcr.ext.backup.impl;

import org.exoplatform.services.jcr.RepositoryService;
import org.exoplatform.services.jcr.config.WorkspaceEntry;
import org.exoplatform.services.jcr.dataflow.DataManager;
import org.exoplatform.services.jcr.ext.backup.BackupChainLog;
import org.exoplatform.services.jcr.ext.backup.BackupManager;
import org.exoplatform.services.jcr.ext.backup.WorkspaceRestoreException;
import org.exoplatform.services.jcr.impl.backup.BackupException;
import org.exoplatform.services.jcr.impl.backup.Backupable;
import org.exoplatform.services.jcr.impl.backup.DataRestor;
import org.exoplatform.services.jcr.impl.backup.JCRRestor;
import org.exoplatform.services.jcr.impl.backup.ResumeException;
import org.exoplatform.services.jcr.impl.backup.Suspendable;
import org.exoplatform.services.jcr.impl.dataflow.persistent.WorkspacePersistentDataManager;
import org.exoplatform.services.jcr.impl.util.io.FileCleanerHolder;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by The eXo Platform SAS.
 *
 * Date: 24 01 2011
 * 
 * @author <a href="mailto:anatoliy.bazko@exoplatform.com.ua">Anatoliy Bazko</a>
 * @version $Id: JobExistedWorkspaceRestoreSameConfig.java 34360 2010-11-11 11:11:11Z tolusha $
 */
public class JobExistedWorkspaceRestoreSameConfig extends JobWorkspaceRestore
{
   /**
    * The logger.
    */
   private static Log log = ExoLogger.getLogger("exo.jcr.component.ext.JobExistedWorkspaceRestoreSameConfig");

   /**
    * JobExistedWorkspaceRestore constructor.
    */
   public JobExistedWorkspaceRestoreSameConfig(RepositoryService repositoryService, BackupManager backupManager,
      String repositoryName, BackupChainLog log, WorkspaceEntry wEntry)
   {
      super(repositoryService, backupManager, repositoryName, log, wEntry);
   }

   /**
    * {@inheritDoc}
    */
   @Override
   protected void restore() throws WorkspaceRestoreException
   {
      // list of data restorers
      List<DataRestor> dataRestorer = new ArrayList<DataRestor>();

      // the list of components to resume
      List<Suspendable> resumeComponents = new ArrayList<Suspendable>();

      try
      {
         // suspend all components
         List<Suspendable> suspendableComponents =
            repositoryService.getRepository(repositoryName).getWorkspaceContainer(wEntry.getName())
               .getComponentInstancesOfType(Suspendable.class);

         for (Suspendable component : suspendableComponents)
         {
            component.suspend();
            resumeComponents.add(component);
         }

         // get all restorers
         List<Backupable> backupable =
            repositoryService.getRepository(repositoryName).getWorkspaceContainer(wEntry.getName())
               .getComponentInstancesOfType(Backupable.class);

         File storageDir = backupChainLog.getBackupConfig().getBackupDir();

         for (Backupable component : backupable)
         {
            File fullBackupDir = JCRRestor.getFullBackupFile(storageDir);
            dataRestorer.add(component.getDataRestorer(fullBackupDir));
         }

         for (DataRestor restorer : dataRestorer)
         {
            restorer.clean();
         }

         for (DataRestor restorer : dataRestorer)
         {
            restorer.restore();
         }
         
         for (DataRestor restorer : dataRestorer)
         {
            restorer.commit();
         }

         // resume components
         for (int i = 0; i < resumeComponents.size() - 1; i++)
         {
            try
            {
               resumeComponents.remove(i).resume();
            }
            catch (ResumeException e)
            {
               log.error("Can't resume component", e);
            }
         }

         // incremental restore
         DataManager dataManager =
            (WorkspacePersistentDataManager)repositoryService.getRepository(repositoryName)
               .getWorkspaceContainer(wEntry.getName()).getComponent(WorkspacePersistentDataManager.class);

         FileCleanerHolder fileCleanHolder =
            (FileCleanerHolder)repositoryService.getRepository(repositoryName).getWorkspaceContainer(wEntry.getName())
               .getComponent(FileCleanerHolder.class);

         JCRRestor restorer = new JCRRestor(dataManager, fileCleanHolder.getFileCleaner());
         for (File incrBackupFile : JCRRestor.getIncrementalFiles(storageDir))
         {
            restorer.incrementalRestore(incrBackupFile);
         }
      }
      catch (Throwable t)
      {
         // rollback
         for (DataRestor restorer : dataRestorer)
         {
            try
            {
               restorer.rollback();
            }
            catch (BackupException e)
            {
               log.error("Can't rollback changes", e);
            }
         }

         throw new WorkspaceRestoreException("Workspace " + wEntry.getName() + " was not restored", t);
      }
      finally
      {
         // close
         for (DataRestor restorer : dataRestorer)
         {
            try
            {
               restorer.close();
            }
            catch (BackupException e)
            {
               log.error("Can't close restorer", e);
            }
         }

         for (Suspendable component : resumeComponents)
         {
            try
            {
               component.resume();
            }
            catch (ResumeException e)
            {
               log.error("Can't resume component", e);
            }
         }
      }
   }
}

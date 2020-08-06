/**
 * This file is part of Expat
 * Copyright (C) 2019, Logical Clocks AB. All rights reserved
 *
 * Expat is free software: you can redistribute it and/or modify it under the terms of
 * the GNU Affero General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 *
 * Expat is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 *
 */
package io.hops.hopsworks.expat.migrations.projects.provenance;

import io.hops.hopsworks.common.hdfs.DistributedFileSystemOps;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.XAttrSetFlag;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.security.UserGroupInformation;
import java.io.File;
import java.io.IOException;
import java.util.EnumSet;

public class HopsClient {
  
  
  public static DistributedFileSystemOps getDFSO(String clientUser) {
    String hadoopHome = System.getenv("HADOOP_HOME");
    if(hadoopHome == null || hadoopHome.equals("")) {
      throw new IllegalArgumentException("env HADOOP_HOME is not set");
    }
    String hadoopConfDir = hadoopHome + "/etc/hadoop";
    //Get the configuration file at found path
    File hadoopConfFile = new File(hadoopConfDir, "core-site.xml");
    if (!hadoopConfFile.exists()) {
      throw new IllegalStateException("No hadoop conf file: core-site.xml");
    }
    
    File hdfsConfFile = new File(hadoopConfDir, "hdfs-site.xml");
    if (!hdfsConfFile.exists()) {
      throw new IllegalStateException("No hdfs conf file: hdfs-site.xml");
    }
    
    Path hdfsPath = new Path(hdfsConfFile.getAbsolutePath());
    Path hadoopPath = new Path(hadoopConfFile.getAbsolutePath());
    
    Configuration conf = new Configuration();
    conf.addResource(hadoopPath);
    conf.addResource(hdfsPath);
    conf.set(CommonConfigurationKeys.FS_PERMISSIONS_UMASK_KEY, "0027");
    
    UserGroupInformation superUser = UserGroupInformation.createRemoteUser(clientUser);
    DistributedFileSystemOps dfso = new DistributedFileSystemOps(superUser, conf);
    return dfso;
  }
  
  public static void upsertXAttr(DistributedFileSystemOps dfso, String path, String name, byte[] value)
    throws IOException {
    EnumSet<XAttrSetFlag> flags = EnumSet.noneOf(XAttrSetFlag.class);
    if(dfso.getXAttr(path, name) != null) {
      flags.add(XAttrSetFlag.REPLACE);
    } else {
      flags.add(XAttrSetFlag.CREATE);
    }
    dfso.setXAttr(path, name, value, flags);
  }
  
  public static void removeXAttr(DistributedFileSystemOps dfso, String path, String name) throws IOException {
    if(dfso.getXAttr(path, name) != null) {
      dfso.removeXAttr(path, name);
    }
  }
  
  public static byte[] getXAttr(DistributedFileSystemOps dfso, String path, String name) throws IOException {
    try {
      return dfso.getXAttr(path,name);
    } catch (RemoteException e) {
      if(e.getClassName().equals("java.io.IOException")
        && e.getMessage().startsWith("At least one of the attributes provided was not found.")) {
        return null;
      }
      throw e;
    } catch (IOException e) {
      throw e;
    }
  }
}

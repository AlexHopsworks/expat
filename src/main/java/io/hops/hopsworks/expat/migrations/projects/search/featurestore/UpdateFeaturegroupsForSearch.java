/**
 * This file is part of Expat
 * Copyright (C) 2020, Logical Clocks AB. All rights reserved
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
package io.hops.hopsworks.expat.migrations.projects.search.featurestore;

import io.hops.hopsworks.common.hdfs.DistributedFileSystemOps;
import io.hops.hopsworks.common.provenance.core.dto.ProvCoreDTO;
import io.hops.hopsworks.common.provenance.core.dto.ProvTypeDTO;
import io.hops.hopsworks.expat.configuration.ConfigurationBuilder;
import io.hops.hopsworks.expat.configuration.ExpatConf;
import io.hops.hopsworks.expat.db.DbConnectionFactory;
import io.hops.hopsworks.expat.migrations.MigrateStep;
import io.hops.hopsworks.expat.migrations.MigrationException;
import io.hops.hopsworks.expat.migrations.RollbackException;
import io.hops.hopsworks.expat.migrations.projects.provenance.HopsClient;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.MarshallerProperties;
import org.eclipse.persistence.oxm.MediaType;
import org.elasticsearch.common.CheckedBiConsumer;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class UpdateFeaturegroupsForSearch implements MigrateStep {
  private final static Logger LOGGER = LogManager.getLogger(UpdateFeaturegroupsForSearch.class);
  
  private final static String GET_ALL_FEATURESTORES = "SELECT id, project_id FROM feature_store";
  private final static int GET_ALL_FEATURESTORES_S_ID = 1;
  private final static int GET_ALL_FEATURESTORES_S_PROJECT_ID = 2;
  private final static String GET_HIVE_MANAGED_FEATUREGROUPS =
    "SELECT f.name, f.version, f.created, f.creator, t.TBL_ID, s.LOCATION " +
    "FROM hopsworks.feature_group f JOIN hopsworks.cached_feature_group c JOIN metastore.TBLS t JOIN metastore.SDS s " +
    "ON f.cached_feature_group_id=c.id AND c.offline_feature_group = t.TBL_ID AND t.SD_ID=s.SD_ID " +
    "WHERE t.TBL_TYPE = \"MANAGED_TABLE\" AND feature_store_id=?";
  private final static int GET_HIVE_MANAGED_FEATUREGROUPS_W_FS_ID = 1;
  private final static int GET_HIVE_MANAGED_FEATUREGROUPS_S_NAME = 1;
  private final static int GET_HIVE_MANAGED_FEATUREGROUPS_S_VERSION = 2;
  private final static int GET_HIVE_MANAGED_FEATUREGROUPS_S_CREATED = 3;
  private final static int GET_HIVE_MANAGED_FEATUREGROUPS_S_CREATOR = 4;
  private final static int GET_HIVE_MANAGED_FEATUREGROUPS_S_TBL_ID = 5;
  private final static int GET_HIVE_MANAGED_FEATUREGROUPS_S_LOCATION = 6;
  
  private final static String GET_USER = "SELECT email FROM users WHERE uid=?";
  private final static int GET_USER_W_ID = 1;
  private final static int GET_USER_S_EMAIL = 1;
  private final static String GET_FG_DESCRIPTION =
    "SELECT PARAM_VALUE FROM metastore.TABLE_PARAMS " +
    "WHERE TBL_ID=? AND PARAM_KEY=?";
  private final static int GET_FG_DESCRIPTION_W_TBL_ID = 1;
  private final static int GET_FG_DESCRIPTION_W_PARAM = 2;
  private final static int GET_FG_DESCRIPTION_S_DESCRIPTION = 1;
  private final static String GET_FG_FEATURES =
    "SELECT c.COLUMN_NAME FROM metastore.TBLS t " +
    "JOIN metastore.SDS s JOIN metastore.COLUMNS_V2 c " +
    "ON t.SD_ID=s.SD_ID AND s.CD_ID=c.CD_ID WHERE t.TBL_ID = ?";
  private final static int GET_FG_FEATURES_W_TBL_ID = 1;
  private final static int GET_FG_FEATURES_S_NAME = 1;
  private final static String GET_PROJECT = "SELECT inode_name FROM project WHERE id=?";
  private final static int GET_PROJECT_W_ID = 1;
  private final static int GET_PROJECT_S_NAME = 1;
  
  protected Connection connection = null;
  DistributedFileSystemOps dfso = null;
  private String hopsUser;
  SimpleDateFormat formatter;
  JAXBContext jaxbContext;
  boolean dryrun = false;
  
  private void setup() throws ConfigurationException, SQLException, JAXBException {
    formatter = new SimpleDateFormat("yyyy-M-dd hh:mm:ss", Locale.ENGLISH);
    jaxbContext = jaxbContext();
    
    connection = DbConnectionFactory.getConnection();
    
    Configuration conf = ConfigurationBuilder.getConfiguration();
    hopsUser = conf.getString(ExpatConf.HOPS_CLIENT_USER);
    if (hopsUser == null) {
      throw new ConfigurationException(ExpatConf.HOPS_CLIENT_USER + " cannot be null");
    }
    dfso = HopsClient.getDFSO(hopsUser);
    dryrun = conf.getBoolean(ExpatConf.DRY_RUN);
  }
  
  private void close() throws SQLException {
    if(connection != null) {
      connection.close();
    }
    if(dfso != null) {
      dfso.close();
    }
  }
  
  @Override
  public void migrate() throws MigrationException {
    LOGGER.info("featuregroup search migration");
    try {
      setup();
      if(dryrun) {
        traverseElements(dryRunFeaturegroup());
      } else {
        traverseElements(migrateFeaturegroup());
      }
    } catch (Exception e) {
      throw new MigrationException("error", e);
    } finally {
      try {
        close();
      } catch (SQLException e) {
        throw new MigrationException("error", e);
      }
    }
  }
  
  @Override
  public void rollback() throws RollbackException {
    LOGGER.info("featuregroup search rollback");
    try {
      setup();
      if(dryrun) {
        traverseElements(dryRunFeaturegroup());
      } else {
        traverseElements(revertFeaturegroup());
      }
    } catch (Exception e) {
      throw new RollbackException("error", e);
    } finally {
      try {
        close();
      } catch (SQLException e) {
        throw new RollbackException("error", e);
      }
    }
  }
  
  private void traverseElements(CheckedBiConsumer<ResultSet, ResultSet, Exception> action)
    throws Exception {
    PreparedStatement allFeaturestoresStmt = null;
    PreparedStatement allFSFeaturegroupsStmt = null;
    
    try {
      connection.setAutoCommit(false);
      allFeaturestoresStmt = connection.prepareStatement(GET_ALL_FEATURESTORES);
      ResultSet allFeaturestoresResultSet = allFeaturestoresStmt.executeQuery();
    
      while (allFeaturestoresResultSet.next()) {
        allFSFeaturegroupsStmt = getFSFeaturegroupsStmt(allFeaturestoresResultSet);
        ResultSet allFSFeaturegroupsResultSet = allFSFeaturegroupsStmt.executeQuery();
        while(allFSFeaturegroupsResultSet.next()) {
          action.accept(allFeaturestoresResultSet, allFSFeaturegroupsResultSet);
        }
        allFSFeaturegroupsStmt.close();
      }
      allFeaturestoresStmt.close();
      connection.commit();
      connection.setAutoCommit(true);
    } finally {
      if(allFSFeaturegroupsStmt != null) {
        allFSFeaturegroupsStmt.close();
      }
      if(allFeaturestoresStmt != null) {
        allFeaturestoresStmt.close();
      }
      close();
    }
  }
  
  private CheckedBiConsumer<ResultSet, ResultSet, Exception> dryRunFeaturegroup() {
    return (ResultSet allFeaturestoresResultSet, ResultSet allFSFeaturegroupsResultSet) -> {
      String projectName = getProjectName(allFeaturestoresResultSet);
      String featuregroupName = allFSFeaturegroupsResultSet.getString(GET_HIVE_MANAGED_FEATUREGROUPS_S_NAME);
      int featuregroupVersion = allFSFeaturegroupsResultSet.getInt(GET_HIVE_MANAGED_FEATUREGROUPS_S_VERSION);
      String featuregroupLocation = allFSFeaturegroupsResultSet.getString(GET_HIVE_MANAGED_FEATUREGROUPS_S_LOCATION);
      String featuregroupPath = getFeaturegroupPath(projectName, featuregroupName, featuregroupVersion);
      if (!featuregroupLocation.endsWith(featuregroupPath)) {
        LOGGER.warn("location mismatch - table:{} computed:{}", featuregroupLocation, featuregroupPath);
        return;
      }
      int featurestoreId = allFeaturestoresResultSet.getInt(GET_ALL_FEATURESTORES_S_ID);
      String description = getDescription(allFSFeaturegroupsResultSet);
      Date createDate =
        formatter.parse(allFSFeaturegroupsResultSet.getString(GET_HIVE_MANAGED_FEATUREGROUPS_S_CREATED));
      String creator = getCreator(allFSFeaturegroupsResultSet);
      List<String> features = getFeatures(allFSFeaturegroupsResultSet);
      FeaturegroupXAttr.FullDTO xattr
        = new FeaturegroupXAttr.FullDTO(featurestoreId, description, createDate, creator, features);
      byte[] val = jaxbMarshal(jaxbContext, xattr).getBytes();
      if (val.length > 13500) {
        LOGGER.warn("xattr too large - skipping attaching features to featuregroup:{}", featuregroupPath);
        xattr = new FeaturegroupXAttr.FullDTO(featurestoreId, description, createDate, creator);
      }
    
      byte[] existingVal = HopsClient.getXAttr(dfso, featuregroupPath, "provenance.featurestore");
      if(existingVal == null) {
        LOGGER.info("featuregroup:{} rollbacked (no value)", featuregroupPath);
      } else {
        FeaturegroupXAttr.FullDTO existingXAttr = jaxbUnmarshal(jaxbContext, existingVal);
        if(existingXAttr.equals(xattr)) {
          LOGGER.info("featuregroup:{} migrated (correct value)", featuregroupPath);
        } else {
          LOGGER.info("featuregroup:{} bad value", featuregroupPath);
        }
      }
    };
  }
  
  private CheckedBiConsumer<ResultSet, ResultSet, Exception> migrateFeaturegroup() {
    return (ResultSet allFeaturestoresResultSet, ResultSet allFSFeaturegroupsResultSet) -> {
      String projectName = getProjectName(allFeaturestoresResultSet);
      String featuregroupName = allFSFeaturegroupsResultSet.getString(GET_HIVE_MANAGED_FEATUREGROUPS_S_NAME);
      int featuregroupVersion = allFSFeaturegroupsResultSet.getInt(GET_HIVE_MANAGED_FEATUREGROUPS_S_VERSION);
      String featuregroupLocation = allFSFeaturegroupsResultSet.getString(GET_HIVE_MANAGED_FEATUREGROUPS_S_LOCATION);
      String featuregroupPath = getFeaturegroupPath(projectName, featuregroupName, featuregroupVersion);
      if(!featuregroupLocation.endsWith(featuregroupPath)) {
        LOGGER.warn("skipped - location mismatch - table:{} computed:{}", featuregroupLocation, featuregroupPath);
        return;
      }
      LOGGER.info("featuregroup:{}", featuregroupPath);
  
      int featurestoreId = allFeaturestoresResultSet.getInt(GET_ALL_FEATURESTORES_S_ID);
      String description = getDescription(allFSFeaturegroupsResultSet);
      Date createDate =
        formatter.parse(allFSFeaturegroupsResultSet.getString(GET_HIVE_MANAGED_FEATUREGROUPS_S_CREATED));
      String creator = getCreator(allFSFeaturegroupsResultSet);
      List<String> features = getFeatures(allFSFeaturegroupsResultSet);
      FeaturegroupXAttr.FullDTO xattr
        = new FeaturegroupXAttr.FullDTO(featurestoreId, description, createDate, creator, features);
      byte[] val = jaxbMarshal(jaxbContext, xattr).getBytes();
      if (val.length > 13500) {
        LOGGER.warn("xattr too large - skipping attaching features to featuregroup:{}", featuregroupPath);
        xattr = new FeaturegroupXAttr.FullDTO(featurestoreId, description, createDate, creator);
        val = jaxbMarshal(jaxbContext, xattr).getBytes();
      }
      try{
        dfso.upsertXAttr(featuregroupPath, "provenance.featurestore", val);
      } catch (RemoteException e) {
        if(e.getMessage().startsWith("At least one of the attributes provided was not found.")) {
          LOGGER.info("no previous xattr featuregroup:{}", featuregroupPath);
          dfso.insertXAttr(featuregroupPath, "provenance.featurestore", val);
        } else {
          throw e;
        }
      }
    };
  }
  
  private CheckedBiConsumer<ResultSet, ResultSet, Exception> revertFeaturegroup() {
    return (ResultSet allFeaturestoresResultSet, ResultSet allFSFeaturegroupsResultSet) -> {
      String projectName = getProjectName(allFeaturestoresResultSet);
      String featuregroupName = allFSFeaturegroupsResultSet.getString(GET_HIVE_MANAGED_FEATUREGROUPS_S_NAME);
      int featuregroupVersion = allFSFeaturegroupsResultSet.getInt(GET_HIVE_MANAGED_FEATUREGROUPS_S_VERSION);
      String featuregroupPath = getFeaturegroupPath(projectName, featuregroupName, featuregroupVersion);
      LOGGER.info("featuregroup:{}", featuregroupPath);
      try {
        dfso.removeXAttr(featuregroupPath, "provenance.featurestore");
      } catch(RemoteException ex) {
        if(ex.getMessage().startsWith("No matching attributes found for remove operation")) {
          //ignore
        } else {
          throw ex;
        }
      }
    };
  }
  
  private PreparedStatement getFSFeaturegroupsStmt(ResultSet allFeaturestoresResultSet) throws SQLException {
    PreparedStatement stmt = connection.prepareStatement(GET_HIVE_MANAGED_FEATUREGROUPS);
    stmt.setInt(GET_HIVE_MANAGED_FEATUREGROUPS_W_FS_ID, allFeaturestoresResultSet.getInt(GET_ALL_FEATURESTORES_S_ID));
    return stmt;
  }
  private PreparedStatement getFGUserStmt(ResultSet allFSFeaturegroupsResultSet) throws SQLException {
    PreparedStatement stmt = connection.prepareStatement(GET_USER);
    stmt.setInt(GET_USER_W_ID, allFSFeaturegroupsResultSet.getInt(GET_HIVE_MANAGED_FEATUREGROUPS_S_CREATOR));
    return stmt;
  }
  private PreparedStatement getFGDescriptionStmt(ResultSet allFSFeaturegroupsResultSet) throws SQLException {
    PreparedStatement stmt = connection.prepareStatement(GET_FG_DESCRIPTION);
    stmt.setInt(GET_FG_DESCRIPTION_W_TBL_ID,
      allFSFeaturegroupsResultSet.getInt(GET_HIVE_MANAGED_FEATUREGROUPS_S_TBL_ID));
    stmt.setString(GET_FG_DESCRIPTION_W_PARAM, "comment");
    return stmt;
  }
  private PreparedStatement getFGFeaturesStmt(ResultSet allFSFeaturegroupsResultSet) throws SQLException {
    PreparedStatement stmt = connection.prepareStatement(GET_FG_FEATURES);
    stmt.setInt(GET_FG_FEATURES_W_TBL_ID, allFSFeaturegroupsResultSet.getInt(GET_HIVE_MANAGED_FEATUREGROUPS_S_TBL_ID));
    return stmt;
  }
  private PreparedStatement getProjectStmt(ResultSet allFeaturestoresResultSet) throws SQLException {
    PreparedStatement stmt = connection.prepareStatement(GET_PROJECT);
    stmt.setInt(GET_PROJECT_W_ID, allFeaturestoresResultSet.getInt(GET_ALL_FEATURESTORES_S_PROJECT_ID));
    return stmt;
  }
  private String getCreator(ResultSet allFSFeaturegroupsResultSet) throws SQLException {
    PreparedStatement fgUserStmt = null;
    try {
      fgUserStmt = getFGUserStmt(allFSFeaturegroupsResultSet);
      ResultSet fgUserResultSet = fgUserStmt.executeQuery();
      if (fgUserResultSet.next()) {
        return fgUserResultSet.getString(GET_USER_S_EMAIL);
      } else {
        throw new IllegalStateException("featuregroup creator not found");
      }
    } finally {
      if(fgUserStmt != null) {
        fgUserStmt.close();
      }
    }
  }
  
  private String getDescription(ResultSet allFSFeaturegroupsResultSet) throws SQLException {
    PreparedStatement fgDescriptionStmt = null;
    try {
      fgDescriptionStmt = getFGDescriptionStmt(allFSFeaturegroupsResultSet);
      ResultSet fgDescriptionResultSet = fgDescriptionStmt.executeQuery();
      if (fgDescriptionResultSet.next()) {
        return fgDescriptionResultSet.getString(GET_FG_DESCRIPTION_S_DESCRIPTION);
      } else {
        throw new IllegalStateException("featuregroup description not found");
      }
    } finally {
      if(fgDescriptionStmt != null) {
        fgDescriptionStmt.close();
      }
    }
  }
  
  private List<String> getFeatures(ResultSet allFSFeaturegroupsResultSet) throws SQLException {
    PreparedStatement fgFeaturesStmt = null;
    try {
      fgFeaturesStmt = getFGFeaturesStmt(allFSFeaturegroupsResultSet);
      ResultSet fgFeaturesResultSet = fgFeaturesStmt.executeQuery();
      List<String> features = new LinkedList<>();
      while (fgFeaturesResultSet.next()) {
        features.add(fgFeaturesResultSet.getString(GET_FG_FEATURES_S_NAME));
      }
      return features;
    } finally {
      if(fgFeaturesStmt != null) {
        fgFeaturesStmt.close();
      }
    }
  }
  
  private String getProjectName(ResultSet allFeaturestoreResultSet) throws SQLException {
    PreparedStatement projectStmt = null;
    try {
      projectStmt = getProjectStmt(allFeaturestoreResultSet);
      ResultSet projectsResultSet = projectStmt.executeQuery();
      if (projectsResultSet.next()) {
        return projectsResultSet.getString(GET_PROJECT_S_NAME);
      } else {
        throw new IllegalStateException("project parent not found");
      }
    } finally {
      if(projectStmt != null) {
        projectStmt.close();
      }
    }
  }
  
  private JAXBContext jaxbContext() throws JAXBException {
    Map<String, Object> properties = new HashMap<>();
    properties.put(MarshallerProperties.JSON_INCLUDE_ROOT, false);
    properties.put(MarshallerProperties.MEDIA_TYPE, MediaType.APPLICATION_JSON);
    JAXBContext context = JAXBContextFactory.createContext(
      new Class[] {
        ProvCoreDTO.class,
        ProvTypeDTO.class,
        FeaturegroupXAttr.FullDTO.class
      },
      properties);
    return context;
  }
  
  private FeaturegroupXAttr.FullDTO jaxbUnmarshal(JAXBContext jaxbContext, byte[] val) throws JAXBException {
    Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
    StreamSource ss = new StreamSource(new StringReader(new String(val)));
    return unmarshaller.unmarshal(ss, FeaturegroupXAttr.FullDTO.class).getValue();
  }
  
  private String jaxbMarshal(JAXBContext jaxbContext, FeaturegroupXAttr.FullDTO xattr) throws JAXBException {
    Marshaller marshaller = jaxbContext.createMarshaller();
    StringWriter sw = new StringWriter();
    marshaller.marshal(xattr, sw);
    return sw.toString();
  }
  
  private String getFeaturegroupPath(String project, String featuregroup, int version) {
    return "/apps/hive/warehouse/" + project.toLowerCase() + "_featurestore.db/" + featuregroup + "_" + version;
  }
}

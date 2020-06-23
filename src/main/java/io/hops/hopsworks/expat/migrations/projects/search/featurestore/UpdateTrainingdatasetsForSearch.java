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
import java.util.Locale;
import java.util.Map;

public class UpdateTrainingdatasetsForSearch implements MigrateStep {
  private final static Logger LOGGER = LogManager.getLogger(UpdateTrainingdatasetsForSearch.class);
  
  private final static String GET_ALL_FEATURESTORES = "SELECT id, project_id FROM feature_store";
  private final static int GET_ALL_FEATURESTORES_S_ID = 1;
  private final static int GET_ALL_FEATURESTORES_S_PROJECT_ID = 2;
  private final static String GET_TRAININGDATASETS = "SELECT name, version, created, creator, description" +
    " FROM training_dataset WHERE feature_store_id=?";
  private final static int GET_TRAININGDATASET_W_FS_ID = 1;
  private final static int GET_TRAININGDATASET_S_NAME = 1;
  private final static int GET_TRAININGDATASET_S_VERSION = 2;
  private final static int GET_TRAININGDATASET_S_CREATED = 3;
  private final static int GET_TRAININGDATASET_S_CREATOR = 4;
  private final static int GET_TRAININGDATASET_S_DESCRIPTION = 5;
  private final static String GET_USER = "SELECT email FROM users WHERE uid=?";
  private final static int GET_USER_W_ID = 1;
  private final static int GET_USER_S_EMAIL = 1;
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
    LOGGER.info("trainingdataset search migration");
    try {
      setup();
      if(dryrun) {
        traverseElements(dryRunTrainingdataset());
      } else {
        traverseElements(migrateTrainingdataset());
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
    LOGGER.info("trainingdataset search rollback");
    try {
      setup();
      if(dryrun) {
        traverseElements(dryRunTrainingdataset());
      } else {
        traverseElements(revertTrainingdataset());
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
    PreparedStatement allFSTrainingdatasetsStmt = null;
    
    try {
      connection.setAutoCommit(false);
      allFeaturestoresStmt = connection.prepareStatement(GET_ALL_FEATURESTORES);
      ResultSet allFeaturestoresResultSet = allFeaturestoresStmt.executeQuery();
    
      while (allFeaturestoresResultSet.next()) {
        allFSTrainingdatasetsStmt = getFSTrainingdatasetsStmt(allFeaturestoresResultSet);
        ResultSet allFSTrainingdatasetsResultSet = allFSTrainingdatasetsStmt.executeQuery();
        while(allFSTrainingdatasetsResultSet.next()) {
          action.accept(allFeaturestoresResultSet, allFSTrainingdatasetsResultSet);
        }
        allFSTrainingdatasetsStmt.close();
      }
      allFeaturestoresStmt.close();
      connection.commit();
      connection.setAutoCommit(true);
    } finally {
      if(allFSTrainingdatasetsStmt != null) {
        allFSTrainingdatasetsStmt.close();
      }
      if(allFeaturestoresStmt != null) {
        allFeaturestoresStmt.close();
      }
      close();
    }
  }
  
  private CheckedBiConsumer<ResultSet, ResultSet, Exception> dryRunTrainingdataset() {
    return (ResultSet allFeaturestoresResultSet, ResultSet allFSTrainingdatasetsResultSet) -> {
      String projectName = getProjectName(allFeaturestoresResultSet);
      String trainingdatasetName = allFSTrainingdatasetsResultSet.getString(GET_TRAININGDATASET_S_NAME);
      int trainingdatasetVersion = allFSTrainingdatasetsResultSet.getInt(GET_TRAININGDATASET_S_VERSION);
      String trainingdatasetPath = getTrainingdatasetPath(projectName, trainingdatasetName, trainingdatasetVersion);
      
      int featurestoreId = allFeaturestoresResultSet.getInt(GET_ALL_FEATURESTORES_S_ID);
      String description = allFSTrainingdatasetsResultSet.getString(GET_TRAININGDATASET_S_DESCRIPTION);
      Date createDate = formatter.parse(allFSTrainingdatasetsResultSet.getString(GET_TRAININGDATASET_S_CREATED));
      String creator = getCreator(allFSTrainingdatasetsResultSet);
      TrainingDatasetXAttrDTO xattr = new TrainingDatasetXAttrDTO(featurestoreId, description, createDate, creator);
      byte[] val = jaxbMarshal(jaxbContext, xattr).getBytes();
      if(val.length > 13500) {
        LOGGER.warn("xattr too large - skipping attaching features to trainingdataset");
        xattr = new TrainingDatasetXAttrDTO(featurestoreId, description, createDate, creator);
      }
      byte[] existingVal = dfso.getXAttr(trainingdatasetPath, "provenance.featurestore");
      if(existingVal == null) {
        LOGGER.info("featuregroup:{} rollbacked (no value)", trainingdatasetPath);
      } else {
        TrainingDatasetXAttrDTO existingXAttr = jaxbUnmarshal(jaxbContext, existingVal);
        if(existingXAttr.equals(xattr)) {
          LOGGER.info("trainingdataset:{} migrated (correct value)", trainingdatasetPath);
        } else {
          LOGGER.info("trainingdataset:{} bad value", trainingdatasetPath);
          LOGGER.info("v1:{}", existingXAttr.toString());
          LOGGER.info("v2:{}", xattr.toString());
        }
      }
    };
  }
  
  private CheckedBiConsumer<ResultSet, ResultSet, Exception> migrateTrainingdataset() {
    return (ResultSet allFeaturestoresResultSet, ResultSet allFSTrainingdatasetsResultSet) -> {
      String projectName = getProjectName(allFeaturestoresResultSet);
      String trainingdatasetName = allFSTrainingdatasetsResultSet.getString(GET_TRAININGDATASET_S_NAME);
      int trainingdatasetVersion = allFSTrainingdatasetsResultSet.getInt(GET_TRAININGDATASET_S_VERSION);
      String trainingdatasetPath = getTrainingdatasetPath(projectName, trainingdatasetName, trainingdatasetVersion);
      LOGGER.info("trainingdataset:{}", trainingdatasetPath);
      
      int featurestoreId = allFeaturestoresResultSet.getInt(GET_ALL_FEATURESTORES_S_ID);
      String description = allFSTrainingdatasetsResultSet.getString(GET_TRAININGDATASET_S_DESCRIPTION);
      Date createDate = formatter.parse(allFSTrainingdatasetsResultSet.getString(GET_TRAININGDATASET_S_CREATED));
      String creator = getCreator(allFSTrainingdatasetsResultSet);
      TrainingDatasetXAttrDTO xattr = new TrainingDatasetXAttrDTO(featurestoreId, description, createDate, creator);
      byte[] val = jaxbMarshal(jaxbContext, xattr).getBytes();
      if(val.length > 13500) {
        LOGGER.warn("xattr too large - skipping attaching features to trainingdataset");
        xattr = new TrainingDatasetXAttrDTO(featurestoreId, description, createDate, creator);
        val = jaxbMarshal(jaxbContext, xattr).getBytes();
      }
      try{
        dfso.upsertXAttr(trainingdatasetPath, "provenance.featurestore", val);
      } catch (RemoteException e) {
        if(e.getMessage().startsWith("At least one of the attributes provided was not found.")) {
          LOGGER.info("no previous xattr featuregroup:{}", trainingdatasetPath);
          dfso.insertXAttr(trainingdatasetPath, "provenance.featurestore", val);
        } else {
          throw e;
        }
      }
    };
  }
  
  private CheckedBiConsumer<ResultSet, ResultSet, Exception> revertTrainingdataset() {
    return (ResultSet allFeaturestoresResultSet, ResultSet allFSTrainingdatasetsResultSet) -> {
      String projectName = getProjectName(allFeaturestoresResultSet);
      String trainingdatasetName = allFSTrainingdatasetsResultSet.getString(GET_TRAININGDATASET_S_NAME);
      int trainingdatasetVersion = allFSTrainingdatasetsResultSet.getInt(GET_TRAININGDATASET_S_VERSION);
      String trainingdatasetPath = getTrainingdatasetPath(projectName, trainingdatasetName, trainingdatasetVersion);
      LOGGER.info("trainingdataset:{}", trainingdatasetPath);
  
      try {
        dfso.removeXAttr(trainingdatasetPath, "provenance.featurestore");
      } catch(RemoteException ex) {
        if(ex.getMessage().startsWith("No matching attributes found for remove operation")) {
          //ignore
        } else {
          throw ex;
        }
      }
    };
  }
  
  private PreparedStatement getFSTrainingdatasetsStmt(ResultSet allFeaturestoresResultSet) throws SQLException {
    PreparedStatement stmt = connection.prepareStatement(GET_TRAININGDATASETS);
    stmt.setInt(GET_TRAININGDATASET_W_FS_ID, allFeaturestoresResultSet.getInt(GET_ALL_FEATURESTORES_S_ID));
    return stmt;
  }
  private PreparedStatement getFGUserStmt(ResultSet allFSTrainingdatasetsResultSet) throws SQLException {
    PreparedStatement stmt = connection.prepareStatement(GET_USER);
    stmt.setInt(GET_USER_W_ID, allFSTrainingdatasetsResultSet.getInt(GET_TRAININGDATASET_S_CREATOR));
    return stmt;
  }
  private PreparedStatement getProjectStmt(ResultSet allFeaturestoresResultSet) throws SQLException {
    PreparedStatement stmt = connection.prepareStatement(GET_PROJECT);
    stmt.setInt(GET_PROJECT_W_ID, allFeaturestoresResultSet.getInt(GET_ALL_FEATURESTORES_S_PROJECT_ID));
    return stmt;
  }
  
  private String getCreator(ResultSet allFSTrainingdatasetsResultSet) throws SQLException {
    PreparedStatement fgUserStmt = null;
    try {
      fgUserStmt = getFGUserStmt(allFSTrainingdatasetsResultSet);
      ResultSet fgUserResultSet = fgUserStmt.executeQuery();
      if (fgUserResultSet.next()) {
        return fgUserResultSet.getString(GET_USER_S_EMAIL);
      } else {
        throw new IllegalStateException("trainingdataset creator not found");
      }
    } finally {
      if(fgUserStmt != null) {
        fgUserStmt.close();
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
        FeaturegroupXAttr.FullDTO.class,
        TrainingDatasetXAttrDTO.class
      },
      properties);
    return context;
  }
  
  private TrainingDatasetXAttrDTO jaxbUnmarshal(JAXBContext jaxbContext, byte[] val) throws JAXBException {
    Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
    StreamSource ss = new StreamSource(new StringReader(new String(val)));
    return unmarshaller.unmarshal(ss, TrainingDatasetXAttrDTO.class).getValue();
  }
  
  private String jaxbMarshal(JAXBContext jaxbContext, TrainingDatasetXAttrDTO xattr) throws JAXBException {
    Marshaller marshaller = jaxbContext.createMarshaller();
    StringWriter sw = new StringWriter();
    marshaller.marshal(xattr, sw);
    return sw.toString();
  }
  
  private String getTrainingdatasetPath( String projectName, String trainingDataset, int version) {
    return "/Projects/" + projectName + "/" + projectName + "_Training_Datasets/" + trainingDataset + "_" + version;
  }
}

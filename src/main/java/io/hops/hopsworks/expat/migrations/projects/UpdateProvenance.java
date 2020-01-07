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
package io.hops.hopsworks.expat.migrations.projects;

import io.hops.hopsworks.common.provenance.core.Provenance;
import io.hops.hopsworks.common.provenance.core.dto.ProvCoreDTO;
import io.hops.hopsworks.common.provenance.core.dto.ProvFeatureDTO;
import io.hops.hopsworks.common.provenance.core.dto.ProvTypeDTO;
import io.hops.hopsworks.common.provenance.util.functional.CheckedConsumer;
import io.hops.hopsworks.expat.db.DbConnectionFactory;
import io.hops.hopsworks.expat.migrations.MigrateStep;
import io.hops.hopsworks.expat.migrations.MigrationException;
import io.hops.hopsworks.expat.migrations.RollbackException;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.persistence.jaxb.JAXBContextFactory;
import org.eclipse.persistence.jaxb.MarshallerProperties;
import org.eclipse.persistence.oxm.MediaType;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;

public class UpdateProvenance implements MigrateStep {
  private final static Logger LOGGER = LogManager.getLogger(UpdateProvenance.class);
  
  private final static String GET_ALL_PROJECTS = "SELECT id, partition_id, inode_pid, inode_name FROM project";
  private final static String GET_PROJECT_INODE =  "SELECT id FROM hops.hdfs_inodes " +
    "WHERE partition_id=? && parent_id=? && name=?";
  private final static String GET_PROJECT_DATASETS = "SELECT id, partition_id, inode_pid, inode_name " +
    "FROM dataset WHERE project_id=?";
  private final static String GET_DATASET_INODE = "SELECT id, meta_enabled, logical_time FROM hops.hdfs_inodes " +
    "WHERE partition_id=? && parent_id=? && name=?";
  private final static String UPDATE_INODE = "UPDATE hops.hdfs_inodes SET meta_enabled=? " +
    "WHERE partition_id=? && parent_id=? && name=?";
  private final static String ADD_PROV_CORE = "INSERT INTO hops.hdfs_file_provenance_xattrs_buffer " +
    "(inode_id, namespace, name, inode_logical_time, value), VALUES (?, ?, ?, ?, ?)";
  
  protected Connection connection;
  
  private void setup() throws SQLException, ConfigurationException {
    connection = DbConnectionFactory.getConnection();
  }
  
  @Override
  public void migrate() throws MigrationException {
    LOGGER.info("Starting project provenance migration");
    try {
      traverseElements(params -> {
        if (params.inodeMetaStatus == 1) {
          byte newMetaStatus = 1;
          try {
            updateMetaStatus(params, newMetaStatus);
            addProvCore(params);
          } catch (SQLException | JAXBException e) {
            throw new CheckedException(e);
          }
        }
      });
    } catch (SQLException | ConfigurationException e) {
      throw new MigrationException("provenance migration issue", e);
    } catch(CheckedException e) {
      throw new MigrationException("provenance migration issue", e.getCause());
    }
    LOGGER.info("Finished projects provenance migration");
  }
  
  @Override
  public void rollback() throws RollbackException {
    LOGGER.info("Starting project provenance rollback");
    try {
      traverseElements(params -> {
        if (params.inodeMetaStatus == 2 || params.inodeMetaStatus == 3) {
          try {
            byte newMetaStatus = 1;
            updateMetaStatus(params, newMetaStatus);
          } catch (SQLException e) {
            throw new CheckedException(e);
          }
        }
      });
    } catch (SQLException | ConfigurationException e) {
      throw new RollbackException("provenance rollback issue", e);
    } catch(CheckedException e) {
      throw new RollbackException("provenance rollback issue", e.getCause());
    }
    LOGGER.info("Finished projects provenance rollback");
  }
  
  private void updateMetaStatus(In params, byte newMetaStatus) throws SQLException {
    params.updateInodeStmt.setByte(1, newMetaStatus);
    params.updateInodeStmt.setLong(2, params.inodePartitionId);
    params.updateInodeStmt.setLong(3, params.inodeParentId);
    params.updateInodeStmt.setString(4, params.inodeName);
    params.updateInodeStmt.addBatch();
  }
  
  private void addProvCore(In params) throws SQLException, JAXBException {
    params.addProvCoreStmt.setLong(1, params.inodeId);
    params.addProvCoreStmt.setByte(2, (byte) 5);
    params.addProvCoreStmt.setString(3, "core");
    params.addProvCoreStmt.setInt(4, params.inodeLogicalTime);
    ProvCoreDTO provCore = new ProvCoreDTO(Provenance.Type.MIN.dto, params.projectId);
    byte[] bProvCore = jaxbParser(params.jaxbContext, provCore).getBytes();
    params.addProvCoreStmt.setBytes(5, bProvCore);
  }
  
  private String jaxbParser(JAXBContext jaxbContext, ProvCoreDTO provCore) throws JAXBException {
    Marshaller marshaller = jaxbContext.createMarshaller();
    StringWriter sw = new StringWriter();
    marshaller.marshal(provCore, sw);
    return sw.toString();
  }
  
  private static class In {
    long projectId;
    long inodePartitionId;
    long inodeParentId;
    long inodeId;
    String inodeName;
    byte inodeMetaStatus;
    int inodeLogicalTime;
  
    PreparedStatement updateInodeStmt;
    PreparedStatement addProvCoreStmt;
  
    JAXBContext jaxbContext;
  }
  
  private static class CheckedException extends Exception {
    public CheckedException(Throwable cause) {
      super(cause);
    }
  }
  
  
  
  private void traverseElements(Function<Long, ProjectAction> actions)
    throws SQLException, ConfigurationException, CheckedException {
    setup();
    
    PreparedStatement allProjectsStmt = null;
    PreparedStatement projectInodeStmt = null;
    PreparedStatement allProjectDatasetsStmt = null;
    PreparedStatement datasetInodeStmt = null;
    PreparedStatement updateInodeStmt = null;
    PreparedStatement addProvCoreStmt = null;
    try {
      connection.setAutoCommit(false);
      allProjectsStmt = connection.prepareStatement(GET_ALL_PROJECTS);
      ResultSet allProjectsResultSet = allProjectsStmt.executeQuery();
      
      while (allProjectsResultSet.next()) {
        int projectId = allProjectsResultSet.getInt(1);
        String projectName = allProjectsResultSet.getString(4);
//        projectInodeStmt = connection.prepareStatement(GET_PROJECT_INODE);
//        projectInodeStmt.setLong(1, allProjectsResultSet.get);
        
        
        LOGGER.info("Trying to migrate project id:{0}, name:{1}", projectId, projectName);
        allProjectDatasetsStmt = connection.prepareStatement(GET_PROJECT_DATASETS);
        allProjectDatasetsStmt.setInt(1, projectId);
        ResultSet allProjectDatasetsResultSet = allProjectDatasetsStmt.executeQuery();
        
        updateInodeStmt = connection.prepareStatement(UPDATE_INODE);
        addProvCoreStmt = connection.prepareStatement(ADD_PROV_CORE);
        while(allProjectDatasetsResultSet.next()) {
          int datasetId = allProjectDatasetsResultSet.getInt(1);
          In in = new In();
          in.updateInodeStmt = updateInodeStmt;
          in.addProvCoreStmt = addProvCoreStmt;
          in.inodePartitionId = allProjectDatasetsResultSet.getLong(2);
          in.inodeParentId = allProjectDatasetsResultSet.getLong(3);
          in.inodeName = allProjectDatasetsResultSet.getString(4);
          LOGGER.info("Trying to migrate dataset id:{0}, name:{1}", datasetId, in.inodeName);
          datasetInodeStmt = connection.prepareStatement(GET_DATASET_INODE);
          datasetInodeStmt.setLong(1, in.inodePartitionId);
          datasetInodeStmt.setLong(2, in.inodeParentId);
          datasetInodeStmt.setString(3, in.inodeName);
          ResultSet datasetInodeResultSet = datasetInodeStmt.executeQuery();
          if (!datasetInodeResultSet.next()) {
            LOGGER.warn("Could not find dataset inode " + in.inodeName);
            continue;
          }
          in.inodeId = datasetInodeResultSet.getLong(1);
          in.inodeMetaStatus = datasetInodeResultSet.getByte(2);
          in.inodeLogicalTime = datasetInodeResultSet.getInt(3);
          datasetAction.accept(in);
        }
        updateInodeStmt.executeBatch();
        LOGGER.info("project:{0} - success", projectName);
        
      }
      connection.commit();
      connection.setAutoCommit(true);
    } finally {
      closeConnections(allProjectsStmt);
      closeConnections(allProjectDatasetsStmt);
      closeConnections(datasetInodeStmt);
      closeConnections(updateInodeStmt);
    }
  }
  
  private JAXBContext jaxbContext(ProvCoreDTO provCore) throws JAXBException {
    Map<String, Object> properties = new HashMap<>();
    properties.put(MarshallerProperties.JSON_INCLUDE_ROOT, false);
    properties.put(MarshallerProperties.MEDIA_TYPE, MediaType.APPLICATION_JSON);
    JAXBContext context = JAXBContextFactory.createContext(
      new Class[] {
        ProvCoreDTO.class,
        ProvTypeDTO.class,
        ProvFeatureDTO.class
      },
      properties);
    return context;
  }
  
  private void closeConnections(PreparedStatement preparedStatement) {
    try {
      if (preparedStatement != null) {
        preparedStatement.close();
      }
    } catch (SQLException ex) {
      //do nothing
    }
  }
}

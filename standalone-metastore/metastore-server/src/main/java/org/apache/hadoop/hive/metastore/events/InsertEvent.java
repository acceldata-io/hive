/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.metastore.events;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.IHMSHandler;
import org.apache.hadoop.hive.metastore.api.ClientCapabilities;
import org.apache.hadoop.hive.metastore.api.ClientCapability;
import org.apache.hadoop.hive.metastore.api.GetTableRequest;
import org.apache.hadoop.hive.metastore.api.InsertEventRequestData;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.utils.FileUtils;
import org.apache.hadoop.hive.metastore.utils.MetaStoreUtils;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

@InterfaceAudience.Public
@InterfaceStability.Stable
public class InsertEvent extends ListenerEvent {

  private static final Logger LOG = LoggerFactory.getLogger(InsertEvent.class);

  private final Table tableObj;
  private final Partition ptnObj;
  private final boolean replace;
  private final List<String> files;
  private List<String> fileChecksums = new ArrayList<>();

  /**
   *
   * @param db name of the database the table is in
   * @param table name of the table being inserted into
   * @param partVals list of partition values, can be null
   * @param insertData the inserted files and their checksums
   * @param status status of insert, true = success, false = failure
   * @param handler handler that is firing the event
   */
  public InsertEvent(String catName, String db, String table, List<String> partVals,
      InsertEventRequestData insertData, boolean status, IHMSHandler handler) throws MetaException,
      NoSuchObjectException {
    super(status, handler);

    GetTableRequest req = new GetTableRequest(db, table);
    req.setCatName(catName);
    // TODO MS-SPLIT Switch this back once HiveMetaStoreClient is moved.
    //req.setCapabilities(HiveMetaStoreClient.TEST_VERSION);
    req.setCapabilities(new ClientCapabilities(
        Lists.newArrayList(ClientCapability.TEST_CAPABILITY, ClientCapability.INSERT_ONLY_TABLES)));
    try {
      this.tableObj = handler.get_table_req(req).getTable();
      if (partVals != null) {
        this.ptnObj = handler.get_partition(MetaStoreUtils.prependNotNullCatToDbName(catName, db),
            table, partVals);
      } else {
        this.ptnObj = null;
      }
    } catch (NoSuchObjectException e) {
      // This is to mimic previous behavior where NoSuchObjectException was thrown through this
      // method.
      throw e;
    } catch (TException e) {
      throw MetaStoreUtils.newMetaException(e);
    }

    // If replace flag is not set by caller, then by default set it to true to maintain backward compatibility
    this.replace = (insertData.isSetReplace() ? insertData.isReplace() : true);
    this.files = resolveFilesAdded(insertData, handler.getConf(), ptnObj, tableObj);
    if (insertData.isSetFilesAddedChecksum()) {
      fileChecksums = insertData.getFilesAddedChecksum();
    }
  }

  private static List<String> resolveFilesAdded(InsertEventRequestData insertData,
      Configuration conf, Partition ptn, Table table) {
    if (insertData.isSetFilesAdded() && insertData.getFilesAdded() != null) {
      return insertData.getFilesAdded();
    }
    return inferFilesFromLocation(conf, ptn, table);
  }

  /**
   * Backward compatibility for legacy clients (e.g. Spark) that fire insert events without
   * populating filesAdded. Infer file paths from the partition or table storage location so
   * DbNotificationListener can still record them in NOTIFICATION_LOG.
   */
  private static List<String> inferFilesFromLocation(Configuration conf, Partition ptn,
      Table table) {
    String location = null;
    if (ptn != null && ptn.getSd() != null) {
      location = ptn.getSd().getLocation();
    }
    if ((location == null || location.isEmpty()) && table != null && table.getSd() != null) {
      location = table.getSd().getLocation();
    }
    if (location == null || location.isEmpty()) {
      return Collections.emptyList();
    }
    try {
      Path path = new Path(location);
      FileSystem fs = path.getFileSystem(conf);
      List<FileStatus> statuses = FileUtils.getFileStatusRecurse(path, fs);
      if (statuses == null || statuses.isEmpty()) {
        return Collections.emptyList();
      }
      List<String> inferredFiles = new ArrayList<>(statuses.size());
      for (FileStatus status : statuses) {
        inferredFiles.add(status.getPath().toString());
      }
      return inferredFiles;
    } catch (IOException e) {
      LOG.warn("Could not infer inserted files from location {}. Using empty file list for " +
          "insert notification.", location, e);
      return Collections.emptyList();
    }
  }

  /**
   * @return Table object
   */
  public Table getTableObj() {
    return tableObj;
  }

  /**
   * @return Partition object
   */
  public Partition getPartitionObj() {
    return ptnObj;
  }

  /**
   * @return The replace flag.
   */
  public boolean isReplace() {
    return replace;
  }

  /**
   * Get list of files created as a result of this DML operation
   *
   * @return list of new files
   */
  public List<String> getFiles() {
    return files;
  }

  /**
   * Get a list of file checksums corresponding to the files created (if available)
   *
   * @return
   */
  public List<String> getFileChecksums() {
    return fileChecksums;
  }
}

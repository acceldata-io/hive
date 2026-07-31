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
package org.apache.hadoop.hive.metastore.txn.jdbc.queries;

import org.apache.hadoop.hive.metastore.DatabaseProduct;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.TxnType;
import org.apache.hadoop.hive.metastore.metrics.Metrics;
import org.apache.hadoop.hive.metastore.metrics.MetricsConstants;
import org.apache.hadoop.hive.metastore.txn.entities.OpenTxn;
import org.apache.hadoop.hive.metastore.txn.entities.OpenTxnList;
import org.apache.hadoop.hive.metastore.txn.entities.TxnStatus;
import org.apache.hadoop.hive.metastore.txn.TxnUtils;
import org.apache.hadoop.hive.metastore.txn.jdbc.QueryHandler;
import org.apache.hadoop.hive.metastore.utils.JavaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GetOpenTxnsListHandler implements QueryHandler<OpenTxnList> {

  private static final Logger LOG = LoggerFactory.getLogger(GetOpenTxnsListHandler.class);

  //language=SQL
  private static final String OPEN_TXNS_QUERY = "SELECT \"TXN_ID\", \"TXN_STATE\", \"TXN_TYPE\", "
      + "(%s - \"TXN_STARTED\") FROM \"TXNS\" ORDER BY \"TXN_ID\"";
  //language=SQL
  private static final String OPEN_TXNS_INFO_QUERY = "SELECT \"TXN_ID\", \"TXN_STATE\", \"TXN_TYPE\", "
      + "(%s - \"TXN_STARTED\"), \"TXN_USER\", \"TXN_HOST\", \"TXN_STARTED\", \"TXN_LAST_HEARTBEAT\" "
      + "FROM \"TXNS\" ORDER BY \"TXN_ID\"";
  
  private final boolean infoFields;
  private final long openTxnTimeOutMillis;

  public GetOpenTxnsListHandler(boolean infoFields, long openTxnTimeOutMillis) {
    this.infoFields = infoFields;
    this.openTxnTimeOutMillis = openTxnTimeOutMillis;
  }

  @Override
  public String getParameterizedQueryString(DatabaseProduct databaseProduct) throws MetaException {
    return String.format(infoFields ? OPEN_TXNS_INFO_QUERY : OPEN_TXNS_QUERY, TxnUtils.getEpochFn(databaseProduct));  
  }

  @Override
  public SqlParameterSource getQueryParameters() {
    return null;
  }

  // We need to figure out the HighWaterMark and the list of open transactions.
  /*
   * This method need guarantees from
   * {@link #openTxns(OpenTxnRequest)} and  {@link #commitTxn(CommitTxnRequest)}.
   * It will look at the TXNS table and find each transaction between the max(txn_id) as HighWaterMark
   * and the max(txn_id) before the TXN_OPENTXN_TIMEOUT period as LowWaterMark.
   * Every transaction that is not found between these will be considered as open, since it may appear later.
   * openTxns must ensure, that no new transaction will be opened with txn_id below LWM and
   * commitTxn must ensure, that no committed transaction will be removed before the time period expires.
   */
  @Override
  public OpenTxnList extractData(ResultSet rs) throws SQLException, DataAccessException {
    /*
     * We can use the maximum txn_id from the TXNS table as high water mark, since the commitTxn and the Initiator
     * guarantees, that the transaction with the highest txn_id will never be removed from the TXNS table.
     * If there is a pending openTxns, that is already acquired it's sequenceId but not yet committed the insert
     * into the TXNS table, will have either a lower txn_id than HWM and will be listed in the openTxn list,
     * or will have a higher txn_id and don't effect this getOpenTxns() call.
     *
     * Materialize TXNS rows first so follow-up lookups against TXN_TO_WRITE_ID / COMPLETED_TXN_COMPONENTS
     * do not run while this ResultSet is still open (MySQL JDBC cannot safely multiplex nested queries).
     */
    Connection dbConn = rs.getStatement().getConnection();
    List<TxnRow> rows = new ArrayList<>();
    while (rs.next()) {
      TxnRow row = new TxnRow();
      row.txnId = rs.getLong(1);
      row.state = TxnStatus.fromString(rs.getString(2));
      row.txnType = TxnType.findByValue(rs.getInt(3));
      row.age = rs.getLong(4);
      if (infoFields) {
        row.user = rs.getString(5);
        row.host = rs.getString(6);
        row.startedTime = rs.getLong(7);
        row.lastHeartBeatTime = rs.getLong(8);
      }
      rows.add(row);
    }

    long hwm = 0;
    long openTxnLowBoundary = 0;
    long skippedAllocated = 0;
    List<OpenTxn> txnInfos = new ArrayList<>();
    // OCR-2541: pre-load allocated txn ids so gap-fill does not treat cleaned writers as open.
    Set<Long> knownAllocated = getAllKnownAllocatedTxnIds(dbConn);

    for (TxnRow row : rows) {
      long txnId = row.txnId;
      hwm = txnId;
      if (row.age < openTxnTimeOutMillis) {
        // We will consider every gap as an open transaction from the previous txnId
        // unless that txn id is already known allocated via write-id / completed components.
        openTxnLowBoundary++;
        while (txnId > openTxnLowBoundary) {
          if (!knownAllocated.contains(openTxnLowBoundary)) {
            txnInfos.add(new OpenTxn(openTxnLowBoundary, TxnStatus.OPEN, TxnType.DEFAULT));
            LOG.debug("Open transaction added for missing value in TXNS {}",
                JavaUtils.txnIdToString(openTxnLowBoundary));
          } else {
            skippedAllocated++;
            LOG.debug("Skipping gap fill for allocated txn {}",
                JavaUtils.txnIdToString(openTxnLowBoundary));
          }
          openTxnLowBoundary++;
        }
      } else {
        openTxnLowBoundary = txnId;
      }
      if (row.state == TxnStatus.COMMITTED) {
        // This is only here, to avoid adding this txnId as possible gap
        continue;
      }
      OpenTxn txnInfo = new OpenTxn(txnId, row.state, row.txnType);
      if (infoFields) {
        txnInfo.setUser(row.user);
        txnInfo.setHost(row.host);
        txnInfo.setStartedTime(row.startedTime);
        txnInfo.setLastHeartBeatTime(row.lastHeartBeatTime);
      }
      txnInfos.add(txnInfo);
    }
    // OCR-2541: empty committed/aborted TXNS cleanup can drop MAX(TXNS) below writer txn ids
    // that still exist in TXN_TO_WRITE_ID / COMPLETED_TXN_COMPONENTS. Raise HWM so readers do
    // not treat those committed write ids as open/invalid.
    hwm = Math.max(hwm, getAllocatedTxnHighWaterMark(dbConn));
    if (skippedAllocated > 0) {
      Metrics.getOrCreateCounter(MetricsConstants.TOTAL_NUM_OPEN_TXN_GAP_FILL_SKIPPED)
          .inc(skippedAllocated);
    }
    LOG.info("Got OpenTxnList with hwm: {} and openTxnList size {} (knownAllocated={}, skippedAllocated={}).",
        hwm, txnInfos.size(), knownAllocated.size(), skippedAllocated);
    return new OpenTxnList(hwm, txnInfos);
  }

  private Set<Long> getAllKnownAllocatedTxnIds(Connection dbConn) throws SQLException {
    Set<Long> known = new HashSet<>();
    // Unquoted identifiers: these helper queries run on a raw JDBC Connection and must work
    // on MySQL even when session sql_mode lacks ANSI_QUOTES (QueryHandler path quotes separately).
    String[] queries = new String[] {
        "SELECT T2W_TXNID FROM TXN_TO_WRITE_ID",
        "SELECT CTC_TXNID FROM COMPLETED_TXN_COMPONENTS"
    };
    try (Statement stmt = dbConn.createStatement()) {
      for (String query : queries) {
        try (ResultSet ids = stmt.executeQuery(query)) {
          while (ids.next()) {
            known.add(ids.getLong(1));
          }
        }
      }
    }
    return known;
  }

  private long getAllocatedTxnHighWaterMark(Connection dbConn) throws SQLException {
    long allocatedHwm = 0;
    // Unquoted identifiers — see getAllKnownAllocatedTxnIds().
    String[] queries = new String[] {
        "SELECT MAX(TXN_ID) FROM TXNS",
        "SELECT MAX(T2W_TXNID) FROM TXN_TO_WRITE_ID",
        "SELECT MAX(CTC_TXNID) FROM COMPLETED_TXN_COMPONENTS"
    };
    try (Statement hwmStmt = dbConn.createStatement()) {
      for (String query : queries) {
        try (ResultSet hwmRs = hwmStmt.executeQuery(query)) {
          if (hwmRs.next()) {
            long value = hwmRs.getLong(1);
            if (!hwmRs.wasNull()) {
              allocatedHwm = Math.max(allocatedHwm, value);
            }
          }
        }
      }
    }
    return allocatedHwm;
  }

  private static final class TxnRow {
    long txnId;
    TxnStatus state;
    TxnType txnType;
    long age;
    String user;
    String host;
    long startedTime;
    long lastHeartBeatTime;
  }
}

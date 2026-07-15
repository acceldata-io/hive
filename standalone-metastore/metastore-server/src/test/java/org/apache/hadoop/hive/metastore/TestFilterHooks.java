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

package org.apache.hadoop.hive.metastore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.hive.metastore.annotation.MetastoreUnitTest;
import org.apache.hadoop.hive.metastore.api.CompactionType;
import org.apache.hadoop.hive.metastore.api.DataConnector;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.GetDatabaseObjectsRequest;
import org.apache.hadoop.hive.metastore.api.GetDatabaseObjectsResponse;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.PartitionSpec;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.TableMeta;
import org.apache.hadoop.hive.metastore.events.PreEventContext;
import org.apache.hadoop.hive.metastore.client.builder.DatabaseBuilder;
import org.apache.hadoop.hive.metastore.client.builder.TableBuilder;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf;
import org.apache.hadoop.hive.metastore.conf.MetastoreConf.ConfVars;
import org.apache.hadoop.hive.metastore.utils.TestTxnDbUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.StringUtils;
import org.junit.Test;

import com.google.common.collect.Lists;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.apache.hadoop.hive.metastore.client.builder.PartitionBuilder;
import org.junit.experimental.categories.Category;

/**
 * Test the filtering behavior at HMS client and HMS server. The configuration at each test
 * changes, and therefore HMS client and server are created for each test case
 */
@Category(MetastoreUnitTest.class)
public class TestFilterHooks {
  public static class DummyMetaStoreFilterHookImpl implements MetaStoreFilterHook {
    private static boolean blockResults = false;

    public DummyMetaStoreFilterHookImpl(Configuration conf) {
    }

    @Override
    public List<String> filterDatabases(List<String> dbList) throws MetaException  {
      if (blockResults) {
        return new ArrayList<>();
      }
      return dbList;
    }

    @Override
    public Database filterDatabase(Database dataBase) throws NoSuchObjectException {
      if (blockResults) {
        throw new NoSuchObjectException("Blocked access");
      }
      return dataBase;
    }

    @Override
    public List<Database> filterDatabaseObjects(List<Database> databaseList) throws MetaException {
      if (blockResults) {
        return new ArrayList<>();
      }
      return databaseList;
    }

    @Override
    public List<String> filterTableNames(String catName, String dbName, List<String> tableList)
        throws MetaException {
      if (blockResults) {
        return new ArrayList<>();
      }
      return tableList;
    }

    @Override
    public Table filterTable(Table table) throws NoSuchObjectException {
      if (blockResults) {
        throw new NoSuchObjectException("Blocked access");
      }
      return table;
    }

    @Override
    public List<Table> filterTables(List<Table> tableList) throws MetaException {
      if (blockResults) {
        return new ArrayList<>();
      }
      return tableList;
    }

    @Override
    @Deprecated
    public List<TableMeta> filterTableMetas(String catName, String dbName,List<TableMeta> tableMetas)
        throws MetaException {
      return filterTableMetas(tableMetas);
    }

    @Override
    public List<TableMeta> filterTableMetas(List<TableMeta> tableMetas) throws MetaException {
      if (blockResults) {
        return new ArrayList<>();
      }
      return tableMetas;
    }

    @Override
    public List<Partition> filterPartitions(List<Partition> partitionList) throws MetaException {
      if (blockResults) {
        return new ArrayList<>();
      }
      return partitionList;
    }

    @Override
    public List<PartitionSpec> filterPartitionSpecs(
        List<PartitionSpec> partitionSpecList) throws MetaException {
      if (blockResults) {
        return new ArrayList<>();
      }
      return partitionSpecList;
    }

    @Override
    public Partition filterPartition(Partition partition) throws NoSuchObjectException {
      if (blockResults) {
        throw new NoSuchObjectException("Blocked access");
      }
      return partition;
    }

    @Override
    public List<String> filterPartitionNames(String catName, String dbName, String tblName,
        List<String> partitionNames) throws MetaException {
      if (blockResults) {
        return new ArrayList<>();
      }
      return partitionNames;
    }

    @Override
    public List<String> filterDataConnectors(List<String> dcList) throws MetaException {
      if (blockResults) {
        return new ArrayList<>();
      }
      return dcList;
    }
  }

  /**
   * HIVE-29378 regression guard helper. Passthrough filter hook that records which of
   * the two SHOW TABLES filter entry points was invoked on the server. After the
   * HMSHandler.get_tables() fix, this handler must be reached via filterTableNames only.
   * If a regression re-introduces the pre-fix getTableObjectsByName + filterTables path,
   * filterTablesCalls will be non-zero and the assertion fires. Serves as a proxy for
   * the un-batched-IN-clause StackOverflow that used to hit at 100k+ tables under
   * server-side filtering (see HIVE-29378, HIVE-24769).
   */
  public static class MethodRecordingFilterHook extends DefaultMetaStoreFilterHookImpl {
    public static final AtomicInteger filterTablesCalls = new AtomicInteger();
    public static final AtomicInteger filterTableNamesCalls = new AtomicInteger();

    public MethodRecordingFilterHook(Configuration conf) { super(conf); }

    public static void reset() {
      filterTablesCalls.set(0);
      filterTableNamesCalls.set(0);
    }

    @Override
    public List<Table> filterTables(List<Table> tableList) throws MetaException {
      filterTablesCalls.incrementAndGet();
      return tableList;
    }

    @Override
    public List<String> filterTableNames(String catName, String dbName, List<String> tableList)
        throws MetaException {
      filterTableNamesCalls.incrementAndGet();
      return tableList;
    }
  }

  /**
   * Pre-event listener that counts PreReadTableEvent invocations. HMSHandler fires
   * PreReadTableEvent only from getTableInternal (get_table for a single table);
   * neither get_tables nor get_table_objects_by_name_req should fire it. If a
   * regression starts firing per-table events during SHOW TABLES, every listener in
   * the chain (StorageBasedAuthorizationProvider, Atlas hook, ...) runs N times --
   * the 121k HDFS-check storm observed at the customer.
   */
  public static class CountingPreEventListener extends MetaStorePreEventListener {
    public static final AtomicInteger tableReadEvents = new AtomicInteger();

    public CountingPreEventListener(Configuration config) { super(config); }

    @Override
    public void onEvent(PreEventContext context) {
      if (context.getEventType() == PreEventContext.PreEventType.READ_TABLE) {
        tableReadEvents.incrementAndGet();
      }
    }
  }

  /**
   * Behavioural boundary marker for HIVE-29378. Its filterTables(List<Table>) rejects
   * everything because it consults Table.owner; its filterTableNames(cat, db, names) is
   * passthrough. After the fix, HMSHandler.get_tables() invokes filterTableNames only,
   * so this hook lets all names through -- the test locks that in as the documented
   * contract. Downstream hook authors whose filterTables() consults Table.owner MUST
   * replicate that logic in filterTableNames() to keep SHOW TABLES filtering intact.
   */
  public static class OwnerBasedFilterHook extends DefaultMetaStoreFilterHookImpl {
    public OwnerBasedFilterHook(Configuration conf) { super(conf); }

    @Override
    public List<Table> filterTables(List<Table> tableList) throws MetaException {
      List<Table> keep = new ArrayList<>();
      for (Table t : tableList) {
        if ("keep".equals(t.getOwner())) {
          keep.add(t);
        }
      }
      return keep;
    }

    @Override
    public List<String> filterTableNames(String catName, String dbName, List<String> tableList)
        throws MetaException {
      return tableList;
    }
  }

  protected static HiveMetaStoreClient client;
  protected static Configuration conf;
  protected static Warehouse warehouse;

  private static final int DEFAULT_LIMIT_PARTITION_REQUEST = 100;

  private static String DBNAME1 = "testdb1";
  private static String DBNAME2 = "testdb2";
  private static final String TAB1 = "tab1";
  private static final String TAB2 = "tab2";
  private static String DCNAME1 = "test_connector1";
  private static String DCNAME2 = "test_connector2";
  private static String mysql_type = "mysql";
  private static String mysql_url = "jdbc:mysql://localhost:3306/hive";
  private static String postgres_type = "postgres";
  private static String postgres_url = "jdbc:postgresql://localhost:5432";


  protected HiveMetaStoreClient createClient(Configuration metaStoreConf) throws Exception {
    try {
      return new HiveMetaStoreClient(metaStoreConf);
    } catch (Throwable e) {
      System.err.println("Unable to open the metastore");
      System.err.println(StringUtils.stringifyException(e));
      throw new Exception(e);
    }
  }

  @BeforeClass
  public static void setUp() throws Exception {
    DummyMetaStoreFilterHookImpl.blockResults = true;
  }

  @Before
  public void setUpForTest() throws Exception {

    conf = MetastoreConf.newMetastoreConf();
    MetastoreConf.setLongVar(conf, ConfVars.THRIFT_CONNECTION_RETRIES, 3);
    MetastoreConf.setBoolVar(conf, ConfVars.HIVE_SUPPORT_CONCURRENCY, false);
    MetastoreConf.setClass(conf, ConfVars.FILTER_HOOK, DummyMetaStoreFilterHookImpl.class,
        MetaStoreFilterHook.class);
    MetastoreConf.setBoolVar(conf, ConfVars.METRICS_ENABLED, true);
    conf.set("hive.key1", "value1");
    conf.set("hive.key2", "http://www.example.com");
    conf.set("hive.key3", "");
    conf.set("hive.key4", "0");
    conf.set("datanucleus.autoCreateTables", "false");
    conf.set("hive.in.test", "true");

    MetastoreConf.setLongVar(conf, ConfVars.BATCH_RETRIEVE_MAX, 2);
    MetastoreConf.setLongVar(conf, ConfVars.LIMIT_PARTITION_REQUEST, DEFAULT_LIMIT_PARTITION_REQUEST);
    MetastoreConf.setVar(conf, ConfVars.STORAGE_SCHEMA_READER_IMPL, "no.such.class");
    MetaStoreTestUtils.setConfForStandloneMode(conf);

    warehouse = new Warehouse(conf);
  }

  @After
  public void tearDown() throws Exception {
    if (client != null) {
      client.close();
    }
  }

  /**
   * This is called in each test after the configuration is set in each test case
   * @throws Exception
   */
  protected void creatEnv(Configuration conf) throws Exception {
    client = createClient(conf);

    client.dropDatabase(DBNAME1, true, true, true);
    client.dropDatabase(DBNAME2, true, true, true);
    client.dropDataConnector(DCNAME1, true, true);
    client.dropDataConnector(DCNAME2, true, true);
    Database db1 = new DatabaseBuilder()
        .setName(DBNAME1)
        .setCatalogName(Warehouse.DEFAULT_CATALOG_NAME)
        .create(client, conf);
    Database db2 = new DatabaseBuilder()
        .setName(DBNAME2)
        .setCatalogName(Warehouse.DEFAULT_CATALOG_NAME)
        .create(client, conf);
    new TableBuilder()
        .setDbName(DBNAME1)
        .setTableName(TAB1)
        .addCol("id", "int")
        .addCol("name", "string")
        .create(client, conf);
    Table tab2 = new TableBuilder()
        .setDbName(DBNAME1)
        .setTableName(TAB2)
        .addCol("id", "int")
        .addPartCol("name", "string")
        .create(client, conf);
    new PartitionBuilder()
        .inTable(tab2)
        .addValue("value1")
        .addToTable(client, conf);
    new PartitionBuilder()
        .inTable(tab2)
        .addValue("value2")
        .addToTable(client, conf);
    DataConnector dc1 = new DataConnector(DCNAME1, mysql_type, mysql_url);
    DataConnector dc2 = new DataConnector(DCNAME2, postgres_type, postgres_url);
    client.createDataConnector(dc1);
    client.createDataConnector(dc2);

    TestTxnDbUtil.cleanDb(conf);
    TestTxnDbUtil.prepDb(conf);
    client.compact2(DBNAME1, TAB1, null, CompactionType.MAJOR, new HashMap<>());
    client.compact2(DBNAME1, TAB2, "name=value1", CompactionType.MINOR, new HashMap<>());
  }

  /**
   * The default configuration should be disable filtering at HMS server
   * Disable the HMS client side filtering in order to see HMS server filtering behavior
   * @throws Exception
   */
  @Test
  public void testHMSServerWithoutFilter() throws Exception {
    MetastoreConf.setBoolVar(conf, ConfVars.METASTORE_CLIENT_FILTER_ENABLED, false);
    DBNAME1 = "db_testHMSServerWithoutFilter_1";
    DBNAME2 = "db_testHMSServerWithoutFilter_2";
    creatEnv(conf);

    assertNotNull(client.getTable(DBNAME1, TAB1));
    assertEquals(2, client.getTables(DBNAME1, "*").size());
    assertEquals(2, client.getAllTables(DBNAME1).size());
    assertEquals(1, client.getTables(DBNAME1, TAB2).size());
    assertEquals(0, client.getAllTables(DBNAME2).size());

    assertNotNull(client.getDatabase(DBNAME1));
    assertEquals(2, client.getDatabases("*testHMSServerWithoutFilter*").size());
    assertEquals(1, client.getDatabases(DBNAME1).size());

    assertNotNull(client.getPartition(DBNAME1, TAB2, "name=value1"));
    assertEquals(1, client.getPartitionsByNames(DBNAME1, TAB2, Lists.newArrayList("name=value1")).size());

    assertEquals(2, client.showCompactions().getCompacts().size());

    assertEquals(2, client.getAllDataConnectorNames().size());
  }

  /**
   * Enable the HMS server side filtering
   * Disable the HMS client side filtering in order to see HMS server filtering behavior
   * @throws Exception
   */
  @Test
  public void testHMSServerWithFilter() throws Exception {
    MetastoreConf.setBoolVar(conf, ConfVars.METASTORE_CLIENT_FILTER_ENABLED, false);
    MetastoreConf.setBoolVar(conf, ConfVars.METASTORE_SERVER_FILTER_ENABLED, true);
    DBNAME1 = "db_testHMSServerWithFilter_1";
    DBNAME2 = "db_testHMSServerWithFilter_2";
    creatEnv(conf);

    testFilterForDb(true);
    testFilterForTables(true);
    testFilterForPartition(true);
    testFilterForCompaction();
    testFilterForDataConnector();
  }

  /**
   * Disable filtering at HMS client
   * By default, the HMS server side filtering is disabled, so we can see HMS client filtering behavior
   * @throws Exception
   */
  @Test
  public void testHMSClientWithoutFilter() throws Exception {
    MetastoreConf.setBoolVar(conf, ConfVars.METASTORE_CLIENT_FILTER_ENABLED, false);
    DBNAME1 = "db_testHMSClientWithoutFilter_1";
    DBNAME2 = "db_testHMSClientWithoutFilter_2";
    creatEnv(conf);

    assertNotNull(client.getTable(DBNAME1, TAB1));
    assertEquals(2, client.getTables(DBNAME1, "*").size());
    assertEquals(2, client.getAllTables(DBNAME1).size());
    assertEquals(1, client.getTables(DBNAME1, TAB2).size());
    assertEquals(0, client.getAllTables(DBNAME2).size());

    assertNotNull(client.getDatabase(DBNAME1));
    assertEquals(2, client.getDatabases("*testHMSClientWithoutFilter*").size());
    assertEquals(1, client.getDatabases(DBNAME1).size());

    assertNotNull(client.getPartition(DBNAME1, TAB2, "name=value1"));
    assertEquals(1, client.getPartitionsByNames(DBNAME1, TAB2, Lists.newArrayList("name=value1")).size());

    assertEquals(2, client.showCompactions().getCompacts().size());

    assertEquals(2, client.getAllDataConnectorNames().size());
  }

  /**
   * By default, the HMS Client side filtering is enabled
   * Disable the HMS server side filtering in order to see HMS client filtering behavior
   * @throws Exception
   */
  @Test
  public void testHMSClientWithFilter() throws Exception {
    MetastoreConf.setBoolVar(conf, ConfVars.METASTORE_SERVER_FILTER_ENABLED, false);
    DBNAME1 = "db_testHMSClientWithFilter_1";
    DBNAME2 = "db_testHMSClientWithFilter_2";
    creatEnv(conf);

    testFilterForDb(false);
    testFilterForTables(false);
    testFilterForPartition(false);
    testFilterForCompaction();
    testFilterForDataConnector();
  }

  protected void testFilterForDb(boolean filterAtServer) throws Exception {

    // Skip this call when testing filter hook at HMS server because HMS server calls authorization
    // API for getDatabase(), and does not call filter hook
    if (!filterAtServer) {
      try {
        assertNotNull(client.getDatabase(DBNAME1));
        fail("getDatabase() should fail with blocking mode");
      } catch (NoSuchObjectException e) {
        // Excepted
      }
    }

    assertEquals(0, client.getDatabases("*").size());
    assertEquals(0, client.getAllDatabases().size());
    assertEquals(0, client.getDatabases(DBNAME1).size());

    GetDatabaseObjectsRequest request = new GetDatabaseObjectsRequest();
    request.setCatalogName(Warehouse.DEFAULT_CATALOG_NAME);
    String testPrefix = DBNAME1.substring(0, DBNAME1.lastIndexOf("_"));
    request.setPattern(testPrefix + "_*");

    // Call the method with filtering enabled
    GetDatabaseObjectsResponse response = client.get_databases_req(request);
    assertEquals("With filtering enabled, should return empty list", 0, response.getDatabasesSize());

    // Temporarily disable blocking to test without filtering
    boolean originalBlockResults = DummyMetaStoreFilterHookImpl.blockResults;
    DummyMetaStoreFilterHookImpl.blockResults = false;

    try {
      response = client.get_databases_req(request);
      System.out.println("Returned databases:");
      for (Database db : response.getDatabases()) {
        System.out.println("DB name: " + db.getName());
      }
      assertEquals("With filtering disabled, should return all databases", 2, response.getDatabasesSize());

      // Verify the returned database objects have the correct names
      Set<String> returnedDbNames = new HashSet<>();
      for (Database db : response.getDatabases()) {
        returnedDbNames.add(db.getName());
      }
      assertTrue("Should contain first database", returnedDbNames.contains(DBNAME1.toLowerCase()));
      assertTrue("Should contain second database", returnedDbNames.contains(DBNAME2.toLowerCase()));
    } finally {
      DummyMetaStoreFilterHookImpl.blockResults = originalBlockResults;
    }
  }

  protected void testFilterForTables(boolean filterAtServer) throws Exception {

    // Skip this call when testing filter hook at HMS server because HMS server calls authorization
    // API for getTable(), and does not call filter hook
    if (!filterAtServer) {
      try {
        client.getTable(DBNAME1, TAB1);
        fail("getTable() should fail with blocking mode");
      } catch (NoSuchObjectException e) {
        // Excepted
      }
    }

    assertEquals(0, client.getTables(DBNAME1, "*").size());
    assertEquals(0, client.getTables(DBNAME1, "*", TableType.MANAGED_TABLE).size());
    assertEquals(0, client.getAllTables(DBNAME1).size());
    assertEquals(0, client.getTables(DBNAME1, TAB2).size());
  }

  protected void testFilterForPartition(boolean filterAtServer) throws Exception {
    try {
      assertNotNull(client.getPartition(DBNAME1, TAB2, "name=value1"));
      fail("getPartition() should fail with blocking mode");
    } catch (NoSuchObjectException e) {
      // Excepted
    }

    if (filterAtServer) {
      // at HMS server, the table of the partitions should be filtered out and result in
      // NoSuchObjectException
      try {
        client.getPartitionsByNames(DBNAME1, TAB2,
            Lists.newArrayList("name=value1")).size();
        fail("getPartitionsByNames() should fail with blocking mode at server side");
      } catch (NoSuchObjectException e) {
        // Excepted
      }
    } else {
      // at HMS client, we cannot filter the table of the partitions due to
      // HIVE-21227: HIVE-20776 causes view access regression
      assertEquals(0, client.getPartitionsByNames(DBNAME1, TAB2,
          Lists.newArrayList("name=value1")).size());
    }
  }

  protected void testFilterForCompaction() throws Exception {
    assertEquals(0, client.showCompactions().getCompacts().size());
  }

  protected void testFilterForDataConnector() throws Exception {
    assertNotNull(client.getDataConnector(DCNAME1));
    assertEquals(0, client.getAllDataConnectorNames().size());
  }

  // ---------------------------------------------------------------------------
  // HIVE-29378 regression guards.
  //
  // HIVE-24769 (fixed 4.0.0-alpha-1) made HMSHandler.get_tables() fetch full Table
  // objects so filter hooks could authorize on Table.owner. That path calls
  // getTableObjectsByName(cat, db, allNames) UN-BATCHED, and DataNucleus builds a
  // recursive OR/AND expression tree from the huge IN clause; at 100k+ tables it
  // StackOverflows in ExpressionCompiler.compileOrAndExpression, and even below
  // that limit each convertToTable adds 5-10 ms per table (HIVE-29378).
  //
  // HIVE-28292 (fixed 4.1.0) rerouted HS2 SHOW TABLES away from get_tables() via
  // listTableNamesByFilter, but left the get_tables() body intact -- so non-HS2
  // Thrift callers (HCatalog, Impala, older HS2 vs newer HMS) still trip the bug.
  //
  // The fix in this branch replaces the getTableObjectsByName + filterTables block
  // with filterTableNamesIfEnabled, matching get_all_tables() and get_tables_by_type().
  // The three tests below defend that fix.
  // ---------------------------------------------------------------------------

  /**
   * HIVE-29378 StackOverflow regression guard. Asserts the server-side filter path
   * for get_tables() goes through filterTableNames() and never through
   * filterTables(List<Table>). Any regression that re-introduces the
   * getTableObjectsByName(cat, db, allNames) call also re-introduces the
   * filterTables call, so this test fires immediately and deterministically -- no
   * 100k-table seeding required.
   */
  @Test
  public void testGetTablesWithServerFilterDoesNotFetchFullTableObjects() throws Exception {
    MetastoreConf.setBoolVar(conf, ConfVars.METASTORE_CLIENT_FILTER_ENABLED, false);
    MetastoreConf.setBoolVar(conf, ConfVars.METASTORE_SERVER_FILTER_ENABLED, true);
    MetastoreConf.setClass(conf, ConfVars.FILTER_HOOK,
        MethodRecordingFilterHook.class, MetaStoreFilterHook.class);
    DBNAME1 = "db_testGetTablesNoFullFetch_1";
    DBNAME2 = "db_testGetTablesNoFullFetch_2";
    creatEnv(conf);

    MethodRecordingFilterHook.reset();
    client.getTables(DBNAME1, "*");
    assertEquals("HIVE-29378: HMSHandler.get_tables() must filter names only. "
            + "Any filterTables(List<Table>) call from this path means the un-batched "
            + "getTableObjectsByName(cat, db, allNames) has been re-introduced -- "
            + "StackOverflow at 100k+ tables and 5-10ms convertToTable per table are back.",
        0, MethodRecordingFilterHook.filterTablesCalls.get());
    assertEquals(1, MethodRecordingFilterHook.filterTableNamesCalls.get());
  }

  /**
   * SHOW TABLES must not fire PreReadTableEvent per table. HMSHandler fires
   * PreReadTableEvent only from getTableInternal (single-table get_table). Any
   * regression that starts firing per-table events during bulk listing causes the
   * full pre-event listener chain (StorageBasedAuthorizationProvider et al.) to
   * run N times -- the 121k HDFS-check storm observed at the customer.
   */
  @Test
  public void testGetTablesDoesNotFireReadTableEventPerTable() throws Exception {
    MetastoreConf.setBoolVar(conf, ConfVars.METASTORE_CLIENT_FILTER_ENABLED, false);
    MetastoreConf.setClass(conf, ConfVars.PRE_EVENT_LISTENERS,
        CountingPreEventListener.class, MetaStorePreEventListener.class);
    DBNAME1 = "db_testNoPerTableEvent_1";
    DBNAME2 = "db_testNoPerTableEvent_2";
    DummyMetaStoreFilterHookImpl.blockResults = false;
    try {
      creatEnv(conf);
      CountingPreEventListener.tableReadEvents.set(0);
      client.getTables(DBNAME1, "*");
      assertEquals("SHOW TABLES must not fire PreReadTableEvent per table "
              + "(each event triggers the full pre-event listener chain, including "
              + "per-table HDFS auth checks -- the customer's 121k-event storm).",
          0, CountingPreEventListener.tableReadEvents.get());
    } finally {
      DummyMetaStoreFilterHookImpl.blockResults = true;
    }
  }

  /**
   * Behavioural boundary marker for HIVE-29378. Pre-fix get_tables() routed through
   * filterTables(List<Table>); post-fix it routes through filterTableNames(). Hooks
   * whose two filter methods return different results (e.g. a custom hook reading
   * Table.owner in filterTables) will now see the filterTableNames result. This
   * test locks the contract in:
   *
   *   OwnerBasedFilterHook.filterTables() would drop both TAB1 and TAB2 (owners
   *   != "keep"). Post-fix that method is never called from get_tables(), so both
   *   names come back. Downstream hook authors that need owner-based filtering in
   *   bulk listing MUST replicate the logic in filterTableNames().
   */
  @Test
  public void testGetTablesFilterHookContractIsFilterTableNames() throws Exception {
    MetastoreConf.setBoolVar(conf, ConfVars.METASTORE_CLIENT_FILTER_ENABLED, false);
    MetastoreConf.setBoolVar(conf, ConfVars.METASTORE_SERVER_FILTER_ENABLED, true);
    MetastoreConf.setClass(conf, ConfVars.FILTER_HOOK, OwnerBasedFilterHook.class,
        MetaStoreFilterHook.class);
    DBNAME1 = "db_testOwnerContract_1";
    DBNAME2 = "db_testOwnerContract_2";
    creatEnv(conf);

    List<String> names = client.getTables(DBNAME1, "*");
    assertEquals("Post-fix get_tables() uses filterTableNames() not filterTables(); "
            + "OwnerBasedFilterHook drops tables in filterTables() but is passthrough in "
            + "filterTableNames(), so both seeded tables must come back.",
        2, names.size());
  }
}

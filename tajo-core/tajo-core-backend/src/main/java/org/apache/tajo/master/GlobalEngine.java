/**
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

package org.apache.tajo.master;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.protocolrecords.GetNewApplicationResponse;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.YarnClient;
import org.apache.hadoop.yarn.client.YarnClientImpl;
import org.apache.hadoop.yarn.exceptions.YarnRemoteException;
import org.apache.hadoop.yarn.service.AbstractService;
import org.apache.hadoop.yarn.util.Records;
import org.apache.tajo.QueryConf;
import org.apache.tajo.QueryId;
import org.apache.tajo.catalog.CatalogService;
import org.apache.tajo.catalog.CatalogUtil;
import org.apache.tajo.catalog.TableDesc;
import org.apache.tajo.catalog.TableMeta;
import org.apache.tajo.catalog.exception.AlreadyExistsTableException;
import org.apache.tajo.catalog.exception.NoSuchTableException;
import org.apache.tajo.catalog.statistics.TableStat;
import org.apache.tajo.engine.exception.EmptyClusterException;
import org.apache.tajo.engine.exception.IllegalQueryStatusException;
import org.apache.tajo.engine.exception.NoSuchQueryIdException;
import org.apache.tajo.engine.exception.UnknownWorkerException;
import org.apache.tajo.engine.parser.DropTableStmt;
import org.apache.tajo.engine.parser.QueryAnalyzer;
import org.apache.tajo.engine.parser.StatementType;
import org.apache.tajo.engine.planner.LogicalOptimizer;
import org.apache.tajo.engine.planner.LogicalPlanner;
import org.apache.tajo.engine.planner.PlanningContext;
import org.apache.tajo.engine.planner.global.GlobalOptimizer;
import org.apache.tajo.engine.planner.global.MasterPlan;
import org.apache.tajo.engine.planner.logical.CreateTableNode;
import org.apache.tajo.engine.planner.logical.LogicalNode;
import org.apache.tajo.engine.planner.logical.LogicalRootNode;
import org.apache.tajo.engine.query.exception.TQLSyntaxError;
import org.apache.tajo.master.TajoMaster.MasterContext;
import org.apache.tajo.storage.StorageManager;
import org.apache.tajo.storage.StorageUtil;
import org.apache.tajo.util.TajoIdUtils;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

@SuppressWarnings("unchecked")
public class GlobalEngine extends AbstractService {
  /** Class Logger */
  private final static Log LOG = LogFactory.getLog(GlobalEngine.class);

  private final MasterContext context;
  private final StorageManager sm;

  private CatalogService catalog;
  private QueryAnalyzer analyzer;
  private LogicalPlanner planner;
  private GlobalPlanner globalPlanner;
  private GlobalOptimizer globalOptimizer;

  // Yarn
  protected YarnClient yarnClient;

  public GlobalEngine(final MasterContext context)
      throws IOException {
    super(GlobalEngine.class.getName());
    this.context = context;
    this.catalog = context.getCatalog();
    this.sm = context.getStorageManager();
  }

  public void start() {
    try  {
      connectYarnClient();
      analyzer = new QueryAnalyzer(context.getCatalog());
      planner = new LogicalPlanner(context.getCatalog());

      globalPlanner = new GlobalPlanner(context.getConf(), context.getCatalog(),
          sm, context.getEventHandler());

      globalOptimizer = new GlobalOptimizer();
    } catch (Throwable t) {
      t.printStackTrace();
    }
    super.start();
  }

  public void stop() {
    super.stop();
    yarnClient.stop();
  }

  public QueryId executeQuery(String tql)
      throws InterruptedException, IOException,
      NoSuchQueryIdException, IllegalQueryStatusException,
      UnknownWorkerException, EmptyClusterException {
    long querySubmittionTime = context.getClock().getTime();
    LOG.info("SQL: " + tql);
    // parse the query
    PlanningContext planningContext = analyzer.parse(tql);

    StatementType cmdType = planningContext.getParseTree().getStatementType();

    if (cmdType == StatementType.CREATE_TABLE || cmdType == StatementType.DROP_TABLE) {
      updateQuery(planningContext);
      return TajoIdUtils.NullQueryId;
    } else {
      LogicalRootNode plan = (LogicalRootNode) createLogicalPlan(planningContext);

      ApplicationAttemptId appAttemptId = submitQuery();
      QueryId queryId = TajoIdUtils.createQueryId(appAttemptId);
      MasterPlan masterPlan = createGlobalPlan(queryId, plan);
      QueryConf queryConf = new QueryConf(context.getConf());
      queryConf.setUser(UserGroupInformation.getCurrentUser().getShortUserName());

      // the output table is given by user
      if (planningContext.hasExplicitOutputTable()) {
        queryConf.setOutputTable(planningContext.getExplicitOutputTable());
      }

      QueryMaster query = new QueryMaster(context, appAttemptId,
          context.getClock(), querySubmittionTime, masterPlan);
      startQuery(queryId, queryConf, query);

      return queryId;
    }
  }

  private ApplicationAttemptId submitQuery() throws YarnRemoteException {
    GetNewApplicationResponse newApp = getNewApplication();
    // Get a new application id
    ApplicationId appId = newApp.getApplicationId();
    System.out.println("Get AppId: " + appId);
    LOG.info("Setting up application submission context for ASM");
    ApplicationSubmissionContext appContext = Records
        .newRecord(ApplicationSubmissionContext.class);

    // set the application id
    appContext.setApplicationId(appId);
    // set the application name
    appContext.setApplicationName("Tajo");

    // Set the priority for the application master
    org.apache.hadoop.yarn.api.records.Priority
        pri = Records.newRecord(org.apache.hadoop.yarn.api.records.Priority.class);
    pri.setPriority(5);
    appContext.setPriority(pri);

    // Set the queue to which this application is to be submitted in the RM
    appContext.setQueue("default");

    // Set up the container launch context for the application master
    ContainerLaunchContext amContainer = Records
        .newRecord(ContainerLaunchContext.class);
    appContext.setAMContainerSpec(amContainer);

    // unmanaged AM
    appContext.setUnmanagedAM(true);
    LOG.info("Setting unmanaged AM");

    // Submit the application to the applications manager
    LOG.info("Submitting application to ASM");
    yarnClient.submitApplication(appContext);

    // Monitor the application to wait for launch state
    ApplicationReport appReport = monitorApplication(appId,
        EnumSet.of(YarnApplicationState.ACCEPTED));
    ApplicationAttemptId attemptId = appReport.getCurrentApplicationAttemptId();
    LOG.info("Launching application with id: " + attemptId);

    return attemptId;
  }

  public boolean updateQuery(String sql) throws IOException {
    LOG.info("SQL: " + sql);
    PlanningContext planningContext = analyzer.parse(sql);
    return updateQuery(planningContext);
  }

  public boolean updateQuery(PlanningContext planningContext) throws IOException {
    StatementType type = planningContext.getParseTree().getStatementType();

    switch (type) {
      case CREATE_TABLE:
        LogicalRootNode plan = (LogicalRootNode) createLogicalPlan(planningContext);
        createTable(plan);
        return true;

      case DROP_TABLE:
        DropTableStmt stmt = (DropTableStmt) planningContext.getParseTree();
        dropTable(stmt.getTableName());
        return true;

      default:
        throw new TQLSyntaxError(planningContext.getRawQuery(), "updateQuery cannot handle such query");
    }
  }

  private LogicalNode createLogicalPlan(PlanningContext planningContext)
      throws IOException {

    LogicalNode plan = planner.createPlan(planningContext);
    plan = LogicalOptimizer.optimize(planningContext, plan);
    LogicalNode optimizedPlan = LogicalOptimizer.pushIndex(plan, sm);
    LOG.info("LogicalPlan:\n" + plan);

    return optimizedPlan;
  }

  private MasterPlan createGlobalPlan(QueryId id, LogicalRootNode rootNode)
      throws IOException {
    MasterPlan globalPlan = globalPlanner.build(id, rootNode);
    return globalOptimizer.optimize(globalPlan);
  }

  private void startQuery(final QueryId queryId, final QueryConf queryConf,
                          final QueryMaster query) {
    context.getAllQueries().put(queryId, query);
    query.init(queryConf);
    query.start();
  }

  private TableDesc createTable(LogicalRootNode root) throws IOException {
    CreateTableNode createTable = (CreateTableNode) root.getSubNode();
    TableMeta meta;

    if (createTable.hasOptions()) {
      meta = CatalogUtil.newTableMeta(createTable.getSchema(),
          createTable.getStorageType(), createTable.getOptions());

    } else {
      meta = CatalogUtil.newTableMeta(createTable.getSchema(),
          createTable.getStorageType());

    }

    return createTable(createTable.getTableName(), meta, createTable.getPath());
  }

  public TableDesc createTable(String tableName, TableMeta meta, Path path) throws IOException {
    if (catalog.existsTable(tableName)) {
      throw new AlreadyExistsTableException(tableName);
    }

    FileSystem fs = path.getFileSystem(context.getConf());

    if(fs.exists(path) && fs.isFile(path)) {
      throw new IOException("ERROR: LOCATION must be a directory.");
    }

    long totalSize = 0;

    try {
      totalSize = sm.calculateSize(path);
    } catch (IOException e) {
      LOG.error("Cannot calculate the size of the relation", e);
    }

    TableStat stat = new TableStat();
    stat.setNumBytes(totalSize);
    meta.setStat(stat);

    TableDesc desc = CatalogUtil.newTableDesc(tableName, meta, path);
    StorageUtil.writeTableMeta(context.getConf(), path, meta);
    catalog.addTable(desc);

    LOG.info("Table " + desc.getId() + " is created (" + desc.getMeta().getStat().getNumBytes() + ")");

    return desc;
  }

  /**
   * Drop a given named table
   *
   * @param tableName to be dropped
   */
  public void dropTable(String tableName) {
    CatalogService catalog = context.getCatalog();

    if (!catalog.existsTable(tableName)) {
      throw new NoSuchTableException(tableName);
    }

    Path path = catalog.getTableDesc(tableName).getPath();
    catalog.deleteTable(tableName);

    try {

      FileSystem fs = path.getFileSystem(context.getConf());
      fs.delete(path, true);
    } catch (IOException e) {
      throw new InternalError(e.getMessage());
    }

    LOG.info("Table \"" + tableName + "\" is dropped.");
  }

  private void connectYarnClient() {
    this.yarnClient = new YarnClientImpl();
    this.yarnClient.init(getConfig());
    this.yarnClient.start();
  }

  public GetNewApplicationResponse getNewApplication()
      throws YarnRemoteException {
    return yarnClient.getNewApplication();
  }

  /**
   * Monitor the submitted application for completion. Kill application if time
   * expires.
   *
   * @param appId
   *          Application Id of application to be monitored
   * @return true if application completed successfully
   * @throws YarnRemoteException
   */
  private ApplicationReport monitorApplication(ApplicationId appId,
                                               Set<YarnApplicationState> finalState) throws YarnRemoteException {

    while (true) {

      // Check app status every 1 second.
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        LOG.debug("Thread sleep in monitoring loop interrupted");
      }

      // Get application report for the appId we are interested in
      ApplicationReport report = yarnClient.getApplicationReport(appId);

      LOG.info("Got application report from ASM for" + ", appId="
          + appId.getId() + ", appAttemptId="
          + report.getCurrentApplicationAttemptId() + ", clientToken="
          + report.getClientToken() + ", appDiagnostics="
          + report.getDiagnostics() + ", appMasterHost=" + report.getHost()
          + ", appQueue=" + report.getQueue() + ", appMasterRpcPort="
          + report.getRpcPort() + ", appStartTime=" + report.getStartTime()
          + ", yarnAppState=" + report.getYarnApplicationState().toString()
          + ", distributedFinalState="
          + report.getFinalApplicationStatus().toString() + ", appTrackingUrl="
          + report.getTrackingUrl() + ", appUser=" + report.getUser());

      YarnApplicationState state = report.getYarnApplicationState();
      if (finalState.contains(state)) {
        return report;
      }
    }
  }
}

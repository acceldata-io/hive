/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.hive.ql.hooks;


import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import io.nats.client.api.StreamInfo;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.QueryPlan;
import org.apache.hadoop.hive.ql.QueryState;
import org.apache.hadoop.hive.ql.exec.ExplainTask;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.exec.TaskRunner;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.exec.mr.ExecDriver;
import org.apache.hadoop.hive.ql.exec.tez.TezTask;
import org.apache.hadoop.hive.ql.hooks.proto.HiveHookEvents.HiveHookEventProto;
import org.apache.hadoop.hive.ql.hooks.proto.HiveHookEvents.MapFieldEntry;
import org.apache.hadoop.yarn.util.Clock;
import org.apache.hadoop.yarn.util.SystemClock;
import org.apache.hive.common.util.ShutdownHookManager;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

public class AdHiveHook implements ExecuteWithHookContext {

    private static final Logger LOG = LoggerFactory.getLogger(AdHiveHook.class.getName());

    // Nats client being singleton, create at the time of class loading, should not cause errors in hook
    // if not connected, try again to connect in any of the run method
    private static AdNatsClient natsClient;
    private static Connection natsConnection;
    private static JetStream jetStream;

    private static final String natsWorkQueue = "hive_queries_events";
    private static final String natsWorkQueueEndpoint = "hive_queries_events_endpoint";

    private static final PublishOptions publishOptions = new PublishOptions.Builder().streamTimeout(java.time.Duration.ofSeconds(5)).build();

    private static Connection initializeNatsClient() {
        if(null != natsConnection && natsConnection.getStatus() == Connection.Status.CONNECTED) {
            return natsConnection;
        } else {
            return natsClient.connectToNATS();
        }
    }
    private static JetStream getJetStream() throws Exception {
        return natsConnection.jetStream();
    }

    private static boolean registerNatsResources(String[] natsServers, String clusterName) {

        if(null == natsClient || !natsClient.isNatsConnected()) {
            try {
                natsClient = new AdNatsClient(natsServers);
                natsConnection = initializeNatsClient();
                jetStream = getJetStream();
                LOG.info("Got NATS resources in place");
            } catch (Exception e) {
                LOG.warn("Error in connecting to NATS ");
                return false;
            }
        }

        String workQueue = natsWorkQueue + "_" + clusterName;
        String workQueueEndpoint = natsWorkQueueEndpoint + "_" + clusterName;

        //initialize nats work queue, use same name for stream and the subject, keeping limit as 1 million
        try {
            if (!natsClient.doesStreamExist(workQueue)) {
                StreamInfo streamInfo = natsClient.registerStream(workQueue, new String[]{workQueue}, 1000000L);
                LOG.debug("JetStream Info for work queue " + streamInfo);
            }
        } catch (Exception e) {
            LOG.warn("Error in getting JetStream info for NATS ", e);
            return false;
        }

        try {
            if (!natsClient.doesStreamExist(workQueueEndpoint)) {
                StreamInfo streamInfo = natsClient.registerStream(workQueueEndpoint, new String[]{workQueueEndpoint}, 1L);
                LOG.debug("JetStream Info for work queue endpoint " + streamInfo);
            }
        } catch (Exception e) {
            LOG.warn("Error in getting JetStream info for NATS ", e);
            return false;
        }
        return true;
    }

    private static final int VERSION = 1;

    private static final SystemClock SYSTEM_CLOCK_INSTANCE = new SystemClock();
    private static final String HIVE_AD_WRITE_HIVE_SERVER_QUERY_ONLY = "hive.hook.write.hive.server.query.only";

    public enum EventType {
        QUERY_SUBMITTED, QUERY_COMPLETED
    }

    public enum OtherInfoType {
        QUERY, STATUS, TEZ, MAPRED, INVOKER_INFO, SESSION_ID, THREAD_NAME, VERSION, CLIENT_IP_ADDRESS,
        HIVE_ADDRESS, HIVE_INSTANCE_TYPE, CONF, PERF, LLAP_APP_ID, ERROR_MSG, ERROR, TIME_TAKEN
    }

    public enum ExecutionMode {
        MR, TEZ, LLAP, SPARK, NONE
    }



    static class EventLogger {

        private final Clock clock;
        EventLogger(Clock clock) {
            this.clock = clock;
        }

        void shutdown() {
            try {
                natsClient.closeNATSConnection(natsConnection);
            } catch (InterruptedException e) {
                e.printStackTrace(System.out);
            }
        }

        void handle(HookContext hookContext) {

            // Note: same hookContext object is used for all the events for a given query, if we try to
            // do it async we have concurrency issues and when query cache is enabled, post event comes
            // before we start the pre hook processing and causes inconsistent events publishing.
            LOG.info("Received  hook." + hookContext.getHookType());
            QueryPlan plan = hookContext.getQueryPlan();

            if (plan == null) {
                LOG.debug("Received null query plan.");
                return;
            }

            String cluster = hookContext.getConf().get("ad.cluster");
            if(null == cluster) {
                LOG.warn("Acceldata cluster config missing, specify ad.cluster in your cluster config");
                return;
            }
            String workQueue = natsWorkQueue + "_" + cluster;
            String workQueueEndpoint = natsWorkQueueEndpoint + "_" + cluster;

            HiveHookEventProto event;
            switch (hookContext.getHookType()) {
                case PRE_EXEC_HOOK:
                    LOG.info("Query is as: " + plan.getQueryStr());
                    event = getPreHookEvent(hookContext);
                    break;
                case POST_EXEC_HOOK:
                    event = getPostHookEvent(hookContext, true);
                    break;
                case ON_FAILURE_HOOK:
                    event = getPostHookEvent(hookContext, false);
                    break;
                default:
                    LOG.warn("Ignoring event of type: {}", hookContext.getHookType());
                    event = null;
            }
            if (event != null) {
                String queryId = plan.getQueryId();
                LOG.info("QueryId: " + queryId);
                try {
                    //start streaming events to NATS subject
                    LOG.info("Going to publish event to NATS");
                    PublishAck ack = jetStream.publish(workQueue, event.toByteArray(), publishOptions);
                    jetStream.publish(workQueueEndpoint,
                            new Long(ack.getSeqno()).toString().getBytes("UTF-8"), publishOptions);
                    LOG.info("Published event to NATS, in work queue " + workQueue);
                } catch (Exception e) {
                    LOG.warn("Error in AdHiveHook during nats queue processing", e);
                }
            }
        }

        private ExecutionMode getExecutionMode(QueryPlan plan) {
            List<ExecDriver> mrTasks = Utilities.getMRTasks(plan.getRootTasks());
            List<TezTask> tezTasks = Utilities.getTezTasks(plan.getRootTasks());
            return getExecutionMode(plan, mrTasks, tezTasks);
        }

        private HiveHookEventProto getPreHookEvent(HookContext hookContext) {
            LOG.info("Received pre-hook notification");
            QueryPlan plan = hookContext.getQueryPlan();

            // Make a copy so that we do not modify hookContext conf.
            HiveConf conf = new HiveConf(hookContext.getConf());

            List<ExecDriver> mrTasks = Utilities.getMRTasks(plan.getRootTasks());
            List<TezTask> tezTasks = Utilities.getTezTasks(plan.getRootTasks());
            ExecutionMode executionMode = getExecutionMode(plan, mrTasks, tezTasks);

            HiveHookEventProto.Builder builder = HiveHookEventProto.newBuilder();
            builder.setEventType(EventType.QUERY_SUBMITTED.name());
            builder.setTimestamp(plan.getQueryStartTime());
            builder.setHiveQueryId(plan.getQueryId());
            builder.setUser(getUser(hookContext));
            builder.setRequestUser(getRequestUser(hookContext));
            String queueName = getQueueName(executionMode, conf);
            if (queueName != null) {
                builder.setQueue(queueName);
            }
            builder.setExecutionMode(executionMode.name());
            builder.addAllTablesRead(getTablesFromEntitySet(hookContext.getInputs()));
            builder.addAllTablesWritten(getTablesFromEntitySet(hookContext.getOutputs()));
            if (hookContext.getOperationId() != null) {
                builder.setOperationId(hookContext.getOperationId());
            }

            try {
                JSONObject queryObj = new JSONObject();
                queryObj.put("queryText", plan.getQueryStr());
                queryObj.put("queryPlan", getExplainPlan(plan, conf, hookContext));
                addMapEntry(builder, OtherInfoType.QUERY, queryObj.toString());
            } catch (Exception e) {
                LOG.warn("Unexpected exception while serializing json.", e);
            }

            addMapEntry(builder, OtherInfoType.TEZ, Boolean.toString(tezTasks.size() > 0));
            addMapEntry(builder, OtherInfoType.MAPRED, Boolean.toString(mrTasks.size() > 0));
            addMapEntry(builder, OtherInfoType.SESSION_ID, hookContext.getSessionId());
            String logID = conf.getLogIdVar("ad_hive_hook_hdp3_log_id ");

            addMapEntry(builder, OtherInfoType.INVOKER_INFO, logID);
            addMapEntry(builder, OtherInfoType.THREAD_NAME, hookContext.getThreadId());
            addMapEntry(builder, OtherInfoType.VERSION, Integer.toString(VERSION));
            addMapEntry(builder, OtherInfoType.CLIENT_IP_ADDRESS, hookContext.getIpAddress());

            String hiveInstanceAddress = hookContext.getHiveInstanceAddress();
            if (hiveInstanceAddress == null) {
                try {
                    hiveInstanceAddress = InetAddress.getLocalHost().getHostAddress();
                } catch (UnknownHostException e) {
                    LOG.warn("Error tyring to get localhost address: ", e);
                }
            }
            addMapEntry(builder, OtherInfoType.HIVE_ADDRESS, hiveInstanceAddress);

            String hiveInstanceType = hookContext.isHiveServerQuery() ? "HS2" : "CLI";
            addMapEntry(builder, OtherInfoType.HIVE_INSTANCE_TYPE, hiveInstanceType);

//            ApplicationId llapId


            conf.stripHiddenConfigurations(conf);
            JSONObject confObj = new JSONObject();
            for (Map.Entry<String, String> setting : conf) {
                try {
                    confObj.put(setting.getKey(), setting.getValue());
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
            addMapEntry(builder, OtherInfoType.CONF, confObj.toString());
            return builder.build();
        }

        private HiveHookEventProto getPostHookEvent(HookContext hookContext, boolean success) {
            QueryPlan plan = hookContext.getQueryPlan();
            LOG.info("Received post-hook notification for: " + plan.getQueryId());

            HiveHookEventProto.Builder builder = HiveHookEventProto.newBuilder();
            builder.setEventType(EventType.QUERY_COMPLETED.name());
            builder.setTimestamp(clock.getTime());
            builder.setHiveQueryId(plan.getQueryId());
            builder.setUser(getUser(hookContext));
            builder.setRequestUser(getRequestUser(hookContext));
            try {
                String executionMode = getExecutionMode(plan).name();
                LOG.info("Acceldata: Execution mode retrieved as " + executionMode);
                builder.setExecutionMode(executionMode);
                long timeTaken = clock.getTime() - hookContext.getQueryPlan().getQueryStartTime();
                addMapEntry(builder, OtherInfoType.TIME_TAKEN, Long.toString(timeTaken));
            } catch (Exception e) {
                // Ignore
            }

            if (hookContext.getOperationId() != null) {
                builder.setOperationId(hookContext.getOperationId());
            }
            addMapEntry(builder, OtherInfoType.STATUS, Boolean.toString(success));


            // Add exception to events
            if (!success) {
                try {

                    Throwable th = null;

                    for (TaskRunner taskRunner : hookContext.getCompleteTaskList()) {
                        if (taskRunner.getTaskResult().getTaskError() != null) {
                            th = taskRunner.getTaskResult().getTaskError();
                        }
                    }

                    if (th != null) {
                        addMapEntry(builder, OtherInfoType.ERROR_MSG, th.getMessage());
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw);
                        th.printStackTrace(pw);

                        String stacktraceString = sw.toString();
                        addMapEntry(builder, OtherInfoType.ERROR, stacktraceString);
                    }

                } catch (Exception e) {
                    // Ignore
                }
            }


            JSONObject perfObj = new JSONObject();
            for (String key : hookContext.getPerfLogger().getEndTimes().keySet()) {
                try {
                    perfObj.put(key, hookContext.getPerfLogger().getDuration(key));
                } catch (JSONException e) {
                    //Ignore
                }
            }
            addMapEntry(builder, OtherInfoType.PERF, perfObj.toString());

            return builder.build();
        }

        private void addMapEntry(HiveHookEventProto.Builder builder, OtherInfoType key, String value) {
            if (value != null) {
                builder.addOtherInfo(
                        MapFieldEntry.newBuilder().setKey(key.name()).setValue(value).build());
            }
        }

        private String getUser(HookContext hookContext) {
            return hookContext.getUgi().getShortUserName();
        }

        private String getRequestUser(HookContext hookContext) {
            String requestuser = hookContext.getUserName();
            if (requestuser == null) {
                requestuser = hookContext.getUgi().getUserName();
            }
            return requestuser;
        }

        private String getQueueName(ExecutionMode mode, HiveConf conf) {
            switch (mode) {
                case LLAP:
//                    return conf.get(HiveConf.ConfVars.LLAP_DAEMON_QUEUE_NAME.varname);
                case MR:
                    return conf.get("mapreduce.job.queuename");
                case TEZ:
                    return conf.get("tez.queue.name");
                case SPARK:
                    return conf.get("spark.queue.name");
                case NONE:
                default:
                    return null;
            }
        }

        private List<String> getTablesFromEntitySet(Set<? extends Entity> entities) {
            List<String> tableNames = new ArrayList<>();
            for (Entity entity : entities) {
                if (entity.getType() == Entity.Type.TABLE) {
                    tableNames.add(entity.getTable().getDbName() + "." + entity.getTable().getTableName());
                }
            }
            return tableNames;
        }

        private ExecutionMode getExecutionMode(QueryPlan plan, List<ExecDriver> mrTasks,
                                               List<TezTask> tezTasks) {
            if (tezTasks.size() > 0) {
                // Need to go in and check if any of the tasks is running in LLAP mode.
                for (TezTask tezTask : tezTasks) {
                    if (tezTask.getWork().getLlapMode()) {
                        LOG.info("Acceldata: The execution mode is as LLAP");
                        return ExecutionMode.LLAP;
                    }
                }
                return ExecutionMode.TEZ;
            } else if (mrTasks.size() > 0) {
                return ExecutionMode.MR;
            } else if (Utilities.getSparkTasks(plan.getRootTasks()).size() > 0) {
                return ExecutionMode.SPARK;
            } else {
                return ExecutionMode.NONE;
            }
        }

        private JSONObject getExplainPlan(QueryPlan plan, HiveConf conf, HookContext hookContext)
                throws Exception {
            // Get explain plan for the query.
//            ExplainConfiguration config = new ExplainConfiguration();
//            config.setFormatted(true);
            ExplainTask explain = new ExplainTask();
            QueryState queryState = new QueryState.Builder().build();
            explain.initialize(queryState, plan, null, null);
            String query = plan.getQueryStr();
            List<Task<?>> rootTasks = plan.getRootTasks();
            JSONObject explainPlan = explain.getJSONPlan(null, rootTasks,
                    plan.getFetchTask(), true, false, false, null, null, null);

            return explainPlan;
        }

        // Singleton using DCL.
        private static volatile EventLogger instance;

        static EventLogger getInstance(HookContext hookContext) {
            if (instance == null) {
                synchronized (EventLogger.class) {
                    if (instance == null) {
                        LOG.info("Getting new event logger instance.");
                        instance = new EventLogger(SYSTEM_CLOCK_INSTANCE);
                        ShutdownHookManager.addShutdownHook(instance::shutdown);
                    }
                }
            }
            return instance;
        }
    }

    @Override
    public void run(HookContext hookContext) throws Exception {
        long timePoint = System.currentTimeMillis();
        try {
            LOG.debug("Inside run method of AdHiveHook");
            if (hookContext.getConf().get(HIVE_AD_WRITE_HIVE_SERVER_QUERY_ONLY, "true").equals("true") && !hookContext.isHiveServerQuery()) {
                LOG.info("Not logging queries without hive server");
                return;
            }

            String cluster = hookContext.getConf().get("ad.cluster");
            if(null == cluster) {
                LOG.warn("Acceldata cluster config missing, specify ad.cluster or ad.events.streaming.servers in your cluster config");
                return;
            }

            if(null == natsClient || !natsClient.isNatsConnected()) {
                LOG.warn("Lost connectivity to NATS, reconnecting");
                boolean natsAvailable = registerNatsResources(hookContext.getConf().get("ad.events.streaming.servers").split(","),
                        hookContext.getConf().get("ad.cluster"));
                if(!natsAvailable) {
                    LOG.warn("Going to miss this app for Pulse, NATS is down");
                    return;
                }
            }

            EventLogger logger = EventLogger.getInstance(hookContext);
            logger.handle(hookContext);
            LOG.info("Streaming cost: " + (System.currentTimeMillis()-timePoint));

        } catch (Exception e) {
            LOG.debug("Exception while processing event: ", e);
        }
    }
}


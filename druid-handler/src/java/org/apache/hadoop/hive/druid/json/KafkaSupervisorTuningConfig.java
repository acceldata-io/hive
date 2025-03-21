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

package org.apache.hadoop.hive.druid.json;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.apache.druid.segment.IndexSpec;
import org.apache.druid.segment.incremental.AppendableIndexSpec;
import org.apache.druid.segment.writeout.SegmentWriteOutMediumFactory;
import org.joda.time.Duration;
import org.joda.time.Period;

import javax.annotation.Nullable;
import java.io.File;

/**
 * This class is copied from druid source code
 * in order to avoid adding additional dependencies on druid-indexing-service.
 */
@SuppressWarnings("unused")
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(name = "kafka", value = KafkaSupervisorTuningConfig.class)})
public class KafkaSupervisorTuningConfig
    extends KafkaIndexTaskTuningConfig implements SeekableStreamSupervisorTuningConfig {
  private static final String DEFAULT_OFFSET_FETCH_PERIOD = "PT30S";

  private final Integer workerThreads;
  private final Integer chatThreads;
  private final Long chatRetries;
  private final Duration httpTimeout;
  private final Duration shutdownTimeout;
  private final Duration offsetFetchPeriod;

  public static KafkaSupervisorTuningConfig defaultConfig() {
    return new KafkaSupervisorTuningConfig(null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null, null, null, null, null);
  }

  public KafkaSupervisorTuningConfig(
      @JsonProperty("maxRowsInMemory") Integer maxRowsInMemory,
      @JsonProperty("maxBytesInMemory") Long maxBytesInMemory,
      @JsonProperty("maxRowsPerSegment") Integer maxRowsPerSegment, @JsonProperty("maxTotalRows") Long maxTotalRows,
      @JsonProperty("intermediatePersistPeriod") Period intermediatePersistPeriod,
      @JsonProperty("basePersistDirectory") File basePersistDirectory,
      @JsonProperty("maxPendingPersists") Integer maxPendingPersists, @JsonProperty("indexSpec") IndexSpec indexSpec,
      @JsonProperty("indexSpecForIntermediatePersists") @Nullable IndexSpec indexSpecForIntermediatePersists,
      // This parameter is left for compatibility when reading existing configs, to be removed in Druid 0.12.
      @JsonProperty("buildV9Directly") Boolean buildV9Directly,
      @JsonProperty("reportParseExceptions") Boolean reportParseExceptions,
      @JsonProperty("handoffConditionTimeout") Long handoffConditionTimeout,
      @JsonProperty("resetOffsetAutomatically") Boolean resetOffsetAutomatically,
      @JsonProperty("segmentWriteOutMediumFactory") @Nullable SegmentWriteOutMediumFactory segmentWriteOutMediumFactory,
      @JsonProperty("workerThreads") Integer workerThreads, @JsonProperty("chatThreads") Integer chatThreads,
      @JsonProperty("chatRetries") Long chatRetries, @JsonProperty("httpTimeout") Period httpTimeout,
      @JsonProperty("shutdownTimeout") Period shutdownTimeout,
      @JsonProperty("offsetFetchPeriod") Period offsetFetchPeriod,
      @JsonProperty("intermediateHandoffPeriod") Period intermediateHandoffPeriod,
      @JsonProperty("logParseExceptions") @Nullable Boolean logParseExceptions,
      @JsonProperty("maxParseExceptions") @Nullable Integer maxParseExceptions,
      @JsonProperty("maxSavedParseExceptions") @Nullable Integer maxSavedParseExceptions) {
    super(maxRowsInMemory, maxBytesInMemory, maxRowsPerSegment, maxTotalRows, intermediatePersistPeriod,
        basePersistDirectory, maxPendingPersists, indexSpec, indexSpecForIntermediatePersists, true,
        reportParseExceptions, handoffConditionTimeout, resetOffsetAutomatically, segmentWriteOutMediumFactory,
        intermediateHandoffPeriod, logParseExceptions, maxParseExceptions, maxSavedParseExceptions);
    this.workerThreads = workerThreads;
    this.chatThreads = chatThreads;
    this.chatRetries = (chatRetries != null ? chatRetries : DEFAULT_CHAT_RETRIES);
    this.httpTimeout = SeekableStreamSupervisorTuningConfig.defaultDuration(httpTimeout, DEFAULT_HTTP_TIMEOUT);
    this.shutdownTimeout =
        SeekableStreamSupervisorTuningConfig.defaultDuration(shutdownTimeout, DEFAULT_SHUTDOWN_TIMEOUT);
    this.offsetFetchPeriod =
        SeekableStreamSupervisorTuningConfig.defaultDuration(offsetFetchPeriod, DEFAULT_OFFSET_FETCH_PERIOD);
  }

  @Override
  @JsonProperty
  public Integer getWorkerThreads() {
    return workerThreads;
  }

  @Override
  @JsonProperty
  public Integer getChatThreads() {
    return chatThreads;
  }

  @Override
  @JsonProperty
  public Long getChatRetries() {
    return chatRetries;
  }

  @Override
  @JsonProperty
  public Duration getHttpTimeout() {
    return httpTimeout;
  }

  @Override
  @JsonProperty
  public Duration getShutdownTimeout() {
    return shutdownTimeout;
  }

  @Override
  public Duration getRepartitionTransitionDuration() {
    // Stopping tasks early for Kafka ingestion on partition set change is not supported yet,
    // just return a default for now.
    return SeekableStreamSupervisorTuningConfig
        .defaultDuration(null, SeekableStreamSupervisorTuningConfig.DEFAULT_REPARTITION_TRANSITION_DURATION);
  }

  @JsonProperty
  public Duration getOffsetFetchPeriod() {
    return offsetFetchPeriod;
  }

  @Override
  public String toString() {
    return "KafkaSupervisorTuningConfig{" + "maxRowsInMemory=" + getMaxRowsInMemory() + ", maxRowsPerSegment="
            + getMaxRowsPerSegment() + ", maxTotalRows=" + getMaxTotalRows() + ", maxBytesInMemory=" +
            getMaxBytesInMemoryOrDefault() + ", intermediatePersistPeriod="
        + getIntermediatePersistPeriod() + ", basePersistDirectory=" + getBasePersistDirectory()
        + ", maxPendingPersists=" + getMaxPendingPersists() + ", indexSpec=" + getIndexSpec()
        + ", reportParseExceptions=" + isReportParseExceptions() + ", handoffConditionTimeout="
        + getHandoffConditionTimeout() + ", resetOffsetAutomatically=" + isResetOffsetAutomatically()
        + ", segmentWriteOutMediumFactory=" + getSegmentWriteOutMediumFactory() + ", workerThreads=" + workerThreads
        + ", chatThreads=" + chatThreads + ", chatRetries=" + chatRetries + ", httpTimeout=" + httpTimeout
        + ", shutdownTimeout=" + shutdownTimeout + ", offsetFetchPeriod=" + offsetFetchPeriod
        + ", intermediateHandoffPeriod=" + getIntermediateHandoffPeriod() + ", logParseExceptions="
        + isLogParseExceptions() + ", maxParseExceptions=" + getMaxParseExceptions() + ", maxSavedParseExceptions="
        + getMaxSavedParseExceptions() + '}';
  }

  @Override
  public KafkaIndexTaskTuningConfig convertToTaskTuningConfig() {
    return new KafkaIndexTaskTuningConfig(getMaxRowsInMemory(), getMaxBytesInMemory(), getMaxRowsPerSegment(),
        getMaxTotalRows(), getIntermediatePersistPeriod(), getBasePersistDirectory(), getMaxPendingPersists(),
        getIndexSpec(), getIndexSpecForIntermediatePersists(), true, isReportParseExceptions(),
        getHandoffConditionTimeout(), isResetOffsetAutomatically(), getSegmentWriteOutMediumFactory(),
        getIntermediateHandoffPeriod(), isLogParseExceptions(), getMaxParseExceptions(), getMaxSavedParseExceptions());
  }

  @Override
  public boolean isSkipBytesInMemoryOverheadCheck() {
    return false;
  }

  @Override
  public AppendableIndexSpec getAppendableIndexSpec() {
    return null;
}
}

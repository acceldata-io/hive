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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.druid.segment.IndexSpec;
import org.apache.druid.segment.incremental.AppendableIndexSpec;
import org.apache.druid.segment.writeout.SegmentWriteOutMediumFactory;
import org.joda.time.Period;

import javax.annotation.Nullable;
import java.io.File;

/**
 * This class is copied from druid source code
 * in order to avoid adding additional dependencies on druid-indexing-service.
 */
public class KafkaIndexTaskTuningConfig extends SeekableStreamIndexTaskTuningConfig {
  @JsonCreator
  public KafkaIndexTaskTuningConfig(
      @JsonProperty("maxRowsInMemory") @Nullable Integer maxRowsInMemory,
      @JsonProperty("maxBytesInMemory") @Nullable Long maxBytesInMemory,
      @JsonProperty("maxRowsPerSegment") @Nullable Integer maxRowsPerSegment,
      @JsonProperty("maxTotalRows") @Nullable Long maxTotalRows,
      @JsonProperty("intermediatePersistPeriod") @Nullable Period intermediatePersistPeriod,
      @JsonProperty("basePersistDirectory") @Nullable File basePersistDirectory,
      @JsonProperty("maxPendingPersists") @Nullable Integer maxPendingPersists,
      @JsonProperty("indexSpec") @Nullable IndexSpec indexSpec,
      @JsonProperty("indexSpecForIntermediatePersists") @Nullable IndexSpec indexSpecForIntermediatePersists,
      // This parameter is left for compatibility when reading existing configs, to be removed in Druid 0.12.
      @JsonProperty("buildV9Directly") @Nullable Boolean buildV9Directly,
      @Deprecated @JsonProperty("reportParseExceptions") @Nullable Boolean reportParseExceptions,
      @JsonProperty("handoffConditionTimeout") @Nullable Long handoffConditionTimeout,
      @JsonProperty("resetOffsetAutomatically") @Nullable Boolean resetOffsetAutomatically,
      @JsonProperty("segmentWriteOutMediumFactory") @Nullable SegmentWriteOutMediumFactory segmentWriteOutMediumFactory,
      @JsonProperty("intermediateHandoffPeriod") @Nullable Period intermediateHandoffPeriod,
      @JsonProperty("logParseExceptions") @Nullable Boolean logParseExceptions,
      @JsonProperty("maxParseExceptions") @Nullable Integer maxParseExceptions,
      @JsonProperty("maxSavedParseExceptions") @Nullable Integer maxSavedParseExceptions
  ) {
    super(
        maxRowsInMemory,
        maxBytesInMemory,
        maxRowsPerSegment,
        maxTotalRows,
        intermediatePersistPeriod,
        basePersistDirectory,
        maxPendingPersists,
        indexSpec,
        indexSpecForIntermediatePersists,
        true,
        reportParseExceptions,
        handoffConditionTimeout,
        resetOffsetAutomatically,
        false,
        segmentWriteOutMediumFactory,
        intermediateHandoffPeriod,
        logParseExceptions,
        maxParseExceptions,
        maxSavedParseExceptions
    );
  }

  @Override
  public boolean isSkipBytesInMemoryOverheadCheck() {
    return false;
  }

  @Override
  public KafkaIndexTaskTuningConfig withBasePersistDirectory(File dir) {
    return new KafkaIndexTaskTuningConfig(
        getMaxRowsInMemory(),
        getMaxBytesInMemory(),
        getMaxRowsPerSegment(),
        getMaxTotalRows(),
        getIntermediatePersistPeriod(),
        dir,
        getMaxPendingPersists(),
        getIndexSpec(),
        getIndexSpecForIntermediatePersists(),
        true,
        isReportParseExceptions(),
        getHandoffConditionTimeout(),
        isResetOffsetAutomatically(),
        getSegmentWriteOutMediumFactory(),
        getIntermediateHandoffPeriod(),
        isLogParseExceptions(),
        getMaxParseExceptions(),
        getMaxSavedParseExceptions()
    );
  }


  @Override
  public String toString() {
    return "KafkaIndexTaskTuningConfig{" +
        "maxRowsInMemory=" + getMaxRowsInMemory() +
        ", maxRowsPerSegment=" + getMaxRowsPerSegment() +
        ", maxTotalRows=" + getMaxTotalRows() +
        ", maxBytesInMemory=" + getMaxBytesInMemory() +
        ", intermediatePersistPeriod=" + getIntermediatePersistPeriod() +
        ", basePersistDirectory=" + getBasePersistDirectory() +
        ", maxPendingPersists=" + getMaxPendingPersists() +
        ", indexSpec=" + getIndexSpec() +
        ", indexSpecForIntermediatePersists=" + getIndexSpecForIntermediatePersists() +
        ", reportParseExceptions=" + isReportParseExceptions() +
        ", handoffConditionTimeout=" + getHandoffConditionTimeout() +
        ", resetOffsetAutomatically=" + isResetOffsetAutomatically() +
        ", segmentWriteOutMediumFactory=" + getSegmentWriteOutMediumFactory() +
        ", intermediateHandoffPeriod=" + getIntermediateHandoffPeriod() +
        ", logParseExceptions=" + isLogParseExceptions() +
        ", maxParseExceptions=" + getMaxParseExceptions() +
        ", maxSavedParseExceptions=" + getMaxSavedParseExceptions() +
        '}';
  }
  @Override
  public AppendableIndexSpec getAppendableIndexSpec() {
    return null;
  }
}

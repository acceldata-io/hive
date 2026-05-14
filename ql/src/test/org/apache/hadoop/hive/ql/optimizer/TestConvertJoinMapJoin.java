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

package org.apache.hadoop.hive.ql.optimizer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.hadoop.hive.ql.plan.Statistics;
import org.junit.jupiter.api.Test;

class TestConvertJoinMapJoin {

  @Test
  void crossProductByteFallback_allowsWhenOnlineSizeWithinBudget() {
    ConvertJoinMapJoin converter = new ConvertJoinMapJoin();
    converter.hashTableLoadFactor = 0.75f;
    Statistics stats = new Statistics(2L, 500L, 0L, 0L);
    assertTrue(
        converter.crossProductBuildSideWithinBroadcastBudgetAfterRowCheck(stats, 1L, 10_000_000L));
  }

  @Test
  void crossProductByteFallback_rejectsWhenOnlineSizeExceedsBudget() {
    ConvertJoinMapJoin converter = new ConvertJoinMapJoin();
    converter.hashTableLoadFactor = 0.75f;
    Statistics stats = new Statistics(50_000L, 50_000_000L, 0L, 0L);
    assertFalse(
        converter.crossProductBuildSideWithinBroadcastBudgetAfterRowCheck(stats, 1L, 10_000_000L));
  }

  @Test
  void crossProductByteFallback_rejectsWhenBudgetTooSmallForEstimatedSize() {
    ConvertJoinMapJoin converter = new ConvertJoinMapJoin();
    converter.hashTableLoadFactor = 0.75f;
    Statistics stats = new Statistics(2L, 500L, 0L, 0L);
    assertFalse(converter.crossProductBuildSideWithinBroadcastBudgetAfterRowCheck(stats, 1L, 1L));
  }

  /**
   * NDV-driven filter selectivity can estimate a tiny lookup at ~2 rows / a few hundred bytes
   * onlineDataSize when {@code hive.xprod.mapjoin.small.table.rows=1}. The row-only gate would
   * reject the broadcast even though the build side is well below the noconditionaltask byte
   * budget. The byte fallback must still admit map-join in that shape.
   */
  @Test
  void crossProductByteFallback_twoRowsTinyOnlineSize() {
    ConvertJoinMapJoin converter = new ConvertJoinMapJoin();
    converter.hashTableLoadFactor = 0.75f;
    final long ndvDrivenRowEstimate = 2L;
    final long tinyDataSizeBytes = 296L;
    Statistics stats = new Statistics(ndvDrivenRowEstimate, tinyDataSizeBytes, 0L, 0L);
    final long xprodRowThreshold = 1L;
    final long noconditionalBudgetBytes = 10_000_000L;
    assertTrue(converter.crossProductBuildSideWithinBroadcastBudgetAfterRowCheck(
        stats, xprodRowThreshold, noconditionalBudgetBytes));
  }

  /**
   * Same small row estimate (2) as the previous case, but with bytes large enough that the build
   * side exceeds the broadcast budget — the byte fallback must reject so the row-only cap still
   * bites.
   */
  @Test
  void crossProductByteFallback_rejectsTwoRowsWhenEstimatedPayloadExceedsBudget() {
    ConvertJoinMapJoin converter = new ConvertJoinMapJoin();
    converter.hashTableLoadFactor = 0.75f;
    Statistics stats = new Statistics(2L, 50_000_000L, 0L, 0L);
    assertFalse(converter.crossProductBuildSideWithinBroadcastBudgetAfterRowCheck(
        stats, 1L, 10_000_000L));
  }
}

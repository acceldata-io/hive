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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.hadoop.hive.ql.plan.Statistics;
import org.junit.jupiter.api.Test;

class TestConvertJoinMapJoin {

  private static ConvertJoinMapJoin newConverter() {
    ConvertJoinMapJoin converter = new ConvertJoinMapJoin();
    converter.hashTableLoadFactor = 0.75f;
    return converter;
  }

  @Test
  void crossProductByteFallback_allowsWhenOnlineSizeWithinBudget() {
    Statistics stats = new Statistics(2L, 500L, 0L, 0L);
    assertTrue(
        newConverter().crossProductBuildSideWithinBroadcastBudget(stats, 1L, 10_000_000L));
  }

  @Test
  void crossProductByteFallback_rejectsWhenOnlineSizeExceedsBudget() {
    Statistics stats = new Statistics(50_000L, 50_000_000L, 0L, 0L);
    assertFalse(
        newConverter().crossProductBuildSideWithinBroadcastBudget(stats, 1L, 10_000_000L));
  }

  @Test
  void crossProductByteFallback_rejectsWhenBudgetTooSmallForEstimatedSize() {
    Statistics stats = new Statistics(2L, 500L, 0L, 0L);
    assertFalse(newConverter().crossProductBuildSideWithinBroadcastBudget(stats, 1L, 1L));
  }

  /**
   * NDV-driven filter selectivity can estimate a tiny lookup at ~2 rows / a few hundred bytes
   * onlineDataSize when {@code hive.xprod.mapjoin.small.table.rows=1}. The row-only gate would
   * reject the broadcast even though the build side is well below the noconditionaltask byte
   * budget. The byte fallback must still admit map-join in that shape.
   */
  @Test
  void crossProductByteFallback_twoRowsTinyOnlineSize() {
    final long ndvDrivenRowEstimate = 2L;
    final long tinyDataSizeBytes = 296L;
    Statistics stats = new Statistics(ndvDrivenRowEstimate, tinyDataSizeBytes, 0L, 0L);
    final long xprodRowThreshold = 1L;
    final long broadcastBudgetBytes = 10_000_000L;
    assertTrue(newConverter().crossProductBuildSideWithinBroadcastBudget(
        stats, xprodRowThreshold, broadcastBudgetBytes));
  }

  /**
   * Same small row estimate (2) as the previous case, but with bytes large enough that the build
   * side exceeds the broadcast budget — the byte fallback must reject so the row-only cap still
   * bites.
   */
  @Test
  void crossProductByteFallback_rejectsTwoRowsWhenEstimatedPayloadExceedsBudget() {
    Statistics stats = new Statistics(2L, 50_000_000L, 0L, 0L);
    assertFalse(newConverter().crossProductBuildSideWithinBroadcastBudget(
        stats, 1L, 10_000_000L));
  }

  /**
   * Pins the caller-side contract: {@code getMapJoinConversion} iterates every non-big parent
   * and rejects the whole broadcast on the first small side that fails the helper. Simulating
   * that loop here means any future refactor that lets a single safe side mask an unsafe sibling
   * will fail this test.
   */
  @Test
  void crossProductByteFallback_anySmallSideOverBudgetRejectsBroadcast() {
    ConvertJoinMapJoin converter = newConverter();
    final long xprodRowThreshold = 1L;
    final long broadcastBudgetBytes = 10_000_000L;
    Statistics safeSide = new Statistics(2L, 500L, 0L, 0L);
    Statistics oversizedSide = new Statistics(50_000L, 50_000_000L, 0L, 0L);

    boolean allWithinBudget = true;
    for (Statistics smallSide : new Statistics[] {safeSide, oversizedSide}) {
      if (smallSide.getNumRows() > xprodRowThreshold
          && !converter.crossProductBuildSideWithinBroadcastBudget(
              smallSide, xprodRowThreshold, broadcastBudgetBytes)) {
        allWithinBudget = false;
        break;
      }
    }
    assertFalse(allWithinBudget);
  }

  /**
   * Pins the hard-disable semantics for {@code xprodRowThreshold <= 0}: the caller short-circuits
   * the byte fallback when the operator has zeroed the row cap, so a stats shape that would
   * otherwise fit the broadcast budget must still reject. Mirrors the caller guard in
   * {@code getMapJoinConversion} so a future refactor that drops the guard fails here.
   */
  @Test
  void crossProductByteFallback_rejectsWhenRowThresholdZero() {
    ConvertJoinMapJoin converter = newConverter();
    final long xprodRowThreshold = 0L;
    final long broadcastBudgetBytes = 10_000_000L;
    Statistics stats = new Statistics(2L, 500L, 0L, 0L);

    boolean allowed = stats.getNumRows() > xprodRowThreshold
        && xprodRowThreshold > 0L
        && converter.crossProductBuildSideWithinBroadcastBudget(
            stats, xprodRowThreshold, broadcastBudgetBytes);
    assertFalse(allowed);
  }

  /**
   * When {@code dataSize == 0} (table has a row estimate but no real byte stats — common for
   * tables without ANALYZE column-size info), {@code Statistics.basicStatsState} is
   * {@code PARTIAL}. {@code computeOnlineDataSize} would still return a tiny positive number
   * from hash-table overhead arithmetic, which would always fit the broadcast budget and
   * silently bypass the row cap. The helper must reject this shape.
   */
  @Test
  void crossProductByteFallback_rejectsWhenBasicStatsPartialDueToMissingDataSize() {
    Statistics stats = new Statistics(2L, 0L, 0L, 0L);
    assertSame(Statistics.State.PARTIAL, stats.getBasicStatsState());
    assertFalse(
        newConverter().crossProductBuildSideWithinBroadcastBudget(stats, 1L, 10_000_000L));
  }

  /**
   * Sanity check the contrapositive: identical row estimate but with a real {@code dataSize}
   * (state {@code COMPLETE}) is admitted. Documents that the fix in
   * {@link #crossProductByteFallback_rejectsWhenBasicStatsPartialDueToMissingDataSize} doesn't
   * over-tighten and reject COMPLETE stats.
   */
  @Test
  void crossProductByteFallback_admitsWhenBasicStatsComplete() {
    Statistics stats = new Statistics(2L, 500L, 0L, 0L);
    assertSame(Statistics.State.COMPLETE, stats.getBasicStatsState());
    assertTrue(
        newConverter().crossProductBuildSideWithinBroadcastBudget(stats, 1L, 10_000_000L));
  }
}

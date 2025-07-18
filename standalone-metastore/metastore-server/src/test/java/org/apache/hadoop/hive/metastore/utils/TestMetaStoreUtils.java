/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.metastore.utils;

import org.apache.hadoop.hive.metastore.annotation.MetastoreUnitTest;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
@Category(MetastoreUnitTest.class)
public class TestMetaStoreUtils {
  private static final TimeZone DEFAULT = TimeZone.getDefault();
  private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
  private final TimeZone timezone;
  private final Timestamp timestamp;
  private final String date;
  private final String timestampString;

  public TestMetaStoreUtils(String zoneId, LocalDateTime localDateTime) {
    this.timezone = TimeZone.getTimeZone(zoneId);
    this.timestamp = Timestamp.from(localDateTime.toInstant(ZoneOffset.UTC));
    this.timestampString = localDateTime.format(FORMATTER);
    this.date = timestamp.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE);
  }

  @Parameterized.Parameters(name = "zoneId={0}, localDateTime={1}")
  public static Collection<Object[]> generateZoneTimestampPairs() {
    List<Object[]> params = new ArrayList<>();
    long minDate = LocalDate.of(1, 1, 1).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
    long maxDate = LocalDate.of(9999, 12, 31).atStartOfDay().toEpochSecond(ZoneOffset.UTC);
    new Random(23).longs(500, minDate, maxDate).forEach(i -> {
      LocalDateTime datetime = LocalDateTime.ofEpochSecond(i, 0, ZoneOffset.UTC);
      for (String zone : ZoneId.SHORT_IDS.values()) {
        params.add(new Object[] { zone, datetime });
      }
    });
    // Timestamp and Date do not have the year 0000. So, the year 0000 gets converted to year 0001.
    params.add(new Object[] {"Asia/Kolkata", LocalDateTime.of(0, 1, 7,22,44,36)});
    generateDaylightSavingTimestampPairs(params);
    return params;
  }

  public static void generateDaylightSavingTimestampPairs(List<Object[]> params) {
    params.add(new Object[] { "America/Anchorage", LocalDateTime.of(2024, 3, 10, 2, 1, 0) });
    params.add(new Object[] { "America/St_Johns", LocalDateTime.of(2024, 3, 10, 2, 1, 0) });
    params.add(new Object[] { "America/Chicago", LocalDateTime.of(2024, 3, 10, 2, 1, 0) });
    params.add(new Object[] { "America/Indiana/Indianapolis", LocalDateTime.of(2024, 3, 10, 2, 1, 0) });
    params.add(new Object[] { "America/Los_Angeles", LocalDateTime.of(2024, 3, 10, 2, 1, 0) });

    params.add(new Object[] { "Europe/Paris", LocalDateTime.of(2024, 3, 31, 2, 2, 2) });

    params.add(new Object[] { "Pacific/Auckland", LocalDateTime.of(2024, 9, 29, 2, 3, 4) });

    params.add(new Object[] { "Australia/Sydney", LocalDateTime.of(2024, 10, 6, 2, 4, 6) });
  }

  @Before
  public void setup() {
    TimeZone.setDefault(timezone);
  }

  @Test
  public void testDateToString() {
    assertEquals(date, MetaStoreUtils.convertDateToString(Date.valueOf(date)));
  }

  @Test
  public void testTimestampToString() {
    assertEquals(timestampString, MetaStoreUtils.convertTimestampToString(timestamp));
  }

  @Test
  public void testStringToDate() {
    assertEquals(Date.valueOf(date), MetaStoreUtils.convertStringToDate(date));
  }

  @Test
  public void testStringToTimestamp() {
    assertEquals(timestamp, MetaStoreUtils.convertStringToTimestamp(timestampString));
  }

  @AfterClass
  public static void tearDown() {
    TimeZone.setDefault(DEFAULT);
  }
}

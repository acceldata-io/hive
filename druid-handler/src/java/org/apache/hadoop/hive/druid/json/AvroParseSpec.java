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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.druid.data.input.impl.DimensionsSpec;
import org.apache.druid.data.input.impl.ParseSpec;
import org.apache.druid.data.input.impl.TimestampSpec;
import org.apache.druid.java.util.common.parsers.JSONPathSpec;
import org.apache.druid.java.util.common.parsers.Parser;

import java.util.Objects;

/**
 * This class is copied from druid source code
 * in order to avoid adding additional dependencies on druid-indexing-service.
 */
public class AvroParseSpec extends ParseSpec {

  @JsonIgnore private final JSONPathSpec flattenSpec;

  @JsonCreator public AvroParseSpec(@JsonProperty("timestampSpec") TimestampSpec timestampSpec,
      @JsonProperty("dimensionsSpec") DimensionsSpec dimensionsSpec,
      @JsonProperty("flattenSpec") JSONPathSpec flattenSpec) {
    super(timestampSpec != null ? timestampSpec : new TimestampSpec(null, null, null),
            dimensionsSpec != null ? dimensionsSpec : new DimensionsSpec(null));

    this.flattenSpec = flattenSpec != null ? flattenSpec : JSONPathSpec.DEFAULT;
  }

  @JsonProperty public JSONPathSpec getFlattenSpec() {
    return flattenSpec;
  }

  @Override public Parser<String, Object> makeParser() {
    // makeParser is only used by StringInputRowParser, which cannot parse avro anyway.
    throw new UnsupportedOperationException("makeParser not supported");
  }

  @Override public ParseSpec withTimestampSpec(TimestampSpec spec) {
    return new AvroParseSpec(spec, getDimensionsSpec(), flattenSpec);
  }

  @Override public ParseSpec withDimensionsSpec(DimensionsSpec spec) {
    return new AvroParseSpec(getTimestampSpec(), spec, flattenSpec);
  }

  @Override public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    final AvroParseSpec that = (AvroParseSpec) o;
    return Objects.equals(flattenSpec, that.flattenSpec);
  }

  @Override public int hashCode() {
    return Objects.hash(super.hashCode(), flattenSpec);
  }
}

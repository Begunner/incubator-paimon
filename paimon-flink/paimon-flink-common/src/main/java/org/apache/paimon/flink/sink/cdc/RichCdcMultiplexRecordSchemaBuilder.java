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

package org.apache.paimon.flink.sink.cdc;

import org.apache.paimon.schema.Schema;
import org.apache.paimon.types.DataType;

import java.util.Map;
import java.util.Optional;

/** Schema builder for {@link RichCdcMultiplexRecord}. */
public class RichCdcMultiplexRecordSchemaBuilder
        implements NewTableSchemaBuilder<RichCdcMultiplexRecord> {

    private final Map<String, String> tableConfig;

    public RichCdcMultiplexRecordSchemaBuilder(Map<String, String> tableConfig) {
        this.tableConfig = tableConfig;
    }

    @Override
    public Optional<Schema> build(RichCdcMultiplexRecord record) {
        Schema.Builder builder = Schema.newBuilder();
        builder.options(tableConfig);

        for (Map.Entry<String, DataType> entry : record.fieldTypes().entrySet()) {
            builder.column(entry.getKey(), entry.getValue(), null);
        }

        Schema schema = builder.primaryKey(record.primaryKeys()).build();

        return Optional.of(schema);
    }
}

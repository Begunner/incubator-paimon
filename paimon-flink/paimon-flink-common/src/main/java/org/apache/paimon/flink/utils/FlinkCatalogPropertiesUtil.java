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

package org.apache.paimon.flink.utils;

import org.apache.flink.table.api.TableColumn;
import org.apache.flink.table.api.WatermarkSpec;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.utils.LogicalTypeParser;
import org.apache.flink.table.types.utils.TypeConversions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.flink.table.descriptors.DescriptorProperties.DATA_TYPE;
import static org.apache.flink.table.descriptors.DescriptorProperties.EXPR;
import static org.apache.flink.table.descriptors.DescriptorProperties.METADATA;
import static org.apache.flink.table.descriptors.DescriptorProperties.NAME;
import static org.apache.flink.table.descriptors.DescriptorProperties.VIRTUAL;
import static org.apache.flink.table.descriptors.DescriptorProperties.WATERMARK;
import static org.apache.flink.table.descriptors.DescriptorProperties.WATERMARK_ROWTIME;
import static org.apache.flink.table.descriptors.DescriptorProperties.WATERMARK_STRATEGY_DATA_TYPE;
import static org.apache.flink.table.descriptors.DescriptorProperties.WATERMARK_STRATEGY_EXPR;
import static org.apache.flink.table.descriptors.Schema.SCHEMA;

/**
 * Utilities for ser/deserializing non-physical columns and watermark into/from a map of string
 * properties.
 */
public class FlinkCatalogPropertiesUtil {

    public static Map<String, String> serializeNonPhysicalColumns(
            Map<String, Integer> indexMap, List<TableColumn> nonPhysicalColumns) {
        Map<String, String> serialized = new HashMap<>();
        for (TableColumn c : nonPhysicalColumns) {
            int index = indexMap.get(c.getName());
            serialized.put(compoundKey(SCHEMA, index, NAME), c.getName());
            serialized.put(
                    compoundKey(SCHEMA, index, DATA_TYPE),
                    c.getType().getLogicalType().asSerializableString());
            if (c instanceof TableColumn.ComputedColumn) {
                TableColumn.ComputedColumn computedColumn = (TableColumn.ComputedColumn) c;
                serialized.put(compoundKey(SCHEMA, index, EXPR), computedColumn.getExpression());
            } else {
                TableColumn.MetadataColumn metadataColumn = (TableColumn.MetadataColumn) c;
                serialized.put(
                        compoundKey(SCHEMA, index, METADATA),
                        metadataColumn.getMetadataAlias().orElse(metadataColumn.getName()));
                serialized.put(
                        compoundKey(SCHEMA, index, VIRTUAL),
                        Boolean.toString(metadataColumn.isVirtual()));
            }
        }
        return serialized;
    }

    public static Map<String, String> serializeWatermarkSpec(WatermarkSpec watermarkSpec) {
        Map<String, String> serializedWatermarkSpec = new HashMap<>();
        String watermarkPrefix = compoundKey(SCHEMA, WATERMARK, 0);
        serializedWatermarkSpec.put(
                compoundKey(watermarkPrefix, WATERMARK_ROWTIME),
                watermarkSpec.getRowtimeAttribute());
        serializedWatermarkSpec.put(
                compoundKey(watermarkPrefix, WATERMARK_STRATEGY_EXPR),
                watermarkSpec.getWatermarkExpr());
        serializedWatermarkSpec.put(
                compoundKey(watermarkPrefix, WATERMARK_STRATEGY_DATA_TYPE),
                watermarkSpec.getWatermarkExprOutputType().getLogicalType().asSerializableString());

        return serializedWatermarkSpec;
    }

    private static final Pattern SCHEMA_COLUMN_NAME_SUFFIX = Pattern.compile("\\d+\\.name");

    public static int nonPhysicalColumnsCount(
            Map<String, String> tableOptions, List<String> physicalColumns) {
        int count = 0;
        for (Map.Entry<String, String> entry : tableOptions.entrySet()) {
            if (isColumnNameKey(entry.getKey()) && !physicalColumns.contains(entry.getValue())) {
                count++;
            }
        }

        return count;
    }

    private static boolean isColumnNameKey(String key) {
        return key.startsWith(SCHEMA)
                && SCHEMA_COLUMN_NAME_SUFFIX.matcher(key.substring(SCHEMA.length() + 1)).matches();
    }

    public static TableColumn deserializeNonPhysicalColumn(Map<String, String> options, int index) {
        String nameKey = compoundKey(SCHEMA, index, NAME);
        String dataTypeKey = compoundKey(SCHEMA, index, DATA_TYPE);
        String exprKey = compoundKey(SCHEMA, index, EXPR);
        String metadataKey = compoundKey(SCHEMA, index, METADATA);
        String virtualKey = compoundKey(SCHEMA, index, VIRTUAL);

        String name = options.get(nameKey);
        DataType dataType =
                TypeConversions.fromLogicalToDataType(
                        LogicalTypeParser.parse(options.get(dataTypeKey)));

        TableColumn column;
        if (options.containsKey(exprKey)) {
            column = TableColumn.computed(name, dataType, options.get(exprKey));
        } else if (options.containsKey(metadataKey)) {
            String metadataAlias = options.get(metadataKey);
            boolean isVirtual = Boolean.parseBoolean(options.get(virtualKey));
            column =
                    metadataAlias.equals(name)
                            ? TableColumn.metadata(name, dataType, isVirtual)
                            : TableColumn.metadata(name, dataType, metadataAlias, isVirtual);
        } else {
            throw new RuntimeException(
                    String.format(
                            "Failed to build non-physical column. Current index is %s, options are %s",
                            index, options));
        }

        return column;
    }

    public static WatermarkSpec deserializeWatermarkSpec(Map<String, String> options) {
        String watermarkPrefixKey = compoundKey(SCHEMA, WATERMARK);

        String rowtimeKey = compoundKey(watermarkPrefixKey, 0, WATERMARK_ROWTIME);
        String exprKey = compoundKey(watermarkPrefixKey, 0, WATERMARK_STRATEGY_EXPR);
        String dataTypeKey = compoundKey(watermarkPrefixKey, 0, WATERMARK_STRATEGY_DATA_TYPE);

        String rowtimeAttribute = options.get(rowtimeKey);
        String watermarkExpressionString = options.get(exprKey);
        DataType watermarkExprOutputType =
                TypeConversions.fromLogicalToDataType(
                        LogicalTypeParser.parse(options.get(dataTypeKey)));

        return new WatermarkSpec(
                rowtimeAttribute, watermarkExpressionString, watermarkExprOutputType);
    }

    public static String compoundKey(Object... components) {
        return Stream.of(components).map(Object::toString).collect(Collectors.joining("."));
    }
}

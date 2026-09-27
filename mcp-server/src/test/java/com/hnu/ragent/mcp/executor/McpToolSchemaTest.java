/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hnu.ragent.mcp.executor;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具参数 Schema 构造器：必填/可选、契约完整性、早失败
 */
@DisplayName("MCP 工具参数 Schema 构造器")
class McpToolSchemaTest {

    @Test
    @DisplayName("必填参数进 required，可选参数不进，类型正确")
    void requiredAndOptional() {
        JsonSchema schema = McpToolSchema.object()
                .required(McpToolSchema.string("query", "检索关键词"))
                .optional(McpToolSchema.integer("count", "结果条数"))
                .optional(McpToolSchema.number("threshold", "阈值"))
                .build();

        assertEquals("object", schema.type());
        assertEquals(List.of("query"), schema.required());
        assertEquals("string", property(schema, "query").get("type"));
        assertEquals("integer", property(schema, "count").get("type"));
        assertEquals("number", property(schema, "threshold").get("type"));
        assertFalse(schema.additionalProperties(), "schema 之外的参数名一律不认");
    }

    @Test
    @DisplayName("title / default / enum 按需写入")
    void optionalAttributes() {
        JsonSchema schema = McpToolSchema.object()
                .required(McpToolSchema.string("receiverPhone", "收货手机号").title("收货手机号"))
                .optional(McpToolSchema.string("freshness", "时效过滤").options(List.of("day", "week")))
                .optional(McpToolSchema.integer("count", "结果条数").defaultTo(5))
                .build();

        assertEquals("收货手机号", property(schema, "receiverPhone").get("title"));
        assertEquals(List.of("day", "week"), property(schema, "freshness").get("enum"));
        assertEquals(5, property(schema, "count").get("default"));
    }

    @Test
    @DisplayName("参数顺序保持声明顺序")
    void keepsDeclarationOrder() {
        JsonSchema schema = McpToolSchema.object()
                .required(McpToolSchema.string("first", "第一个"))
                .optional(McpToolSchema.string("second", "第二个"))
                .optional(McpToolSchema.string("third", "第三个"))
                .build();

        assertEquals(new ArrayList<>(List.of("first", "second", "third")), new ArrayList<>(schema.properties().keySet()));
    }

    @Test
    @DisplayName("参数名为空、描述为空、参数重名都在构造期失败")
    void failsFast() {
        assertThrows(IllegalArgumentException.class, () -> McpToolSchema.string("", "描述"));
        assertThrows(IllegalArgumentException.class, () -> McpToolSchema.string("  ", "描述"));
        assertThrows(IllegalArgumentException.class, () -> McpToolSchema.string("query", ""));
        assertThrows(IllegalArgumentException.class, () -> McpToolSchema.object().required(null));
        assertThrows(IllegalStateException.class, () -> McpToolSchema.object()
                .required(McpToolSchema.string("query", "第一次"))
                .optional(McpToolSchema.string("query", "第二次")));
    }

    @Test
    @DisplayName("构造出的 schema 只读，外部改不动")
    void schemaIsImmutable() {
        JsonSchema schema = McpToolSchema.object()
                .required(McpToolSchema.string("query", "检索关键词"))
                .build();

        assertThrows(UnsupportedOperationException.class, () -> schema.properties().put("extra", Map.of()));
    }

    private Map<String, Object> property(JsonSchema schema, String name) {
        Object value = schema.properties().get(name);
        assertTrue(value instanceof Map, "参数必须以对象形式写入 schema");
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }
}

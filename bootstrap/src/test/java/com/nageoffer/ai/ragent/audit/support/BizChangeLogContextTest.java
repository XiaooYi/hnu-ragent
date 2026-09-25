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

package com.nageoffer.ai.ragent.audit.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext.BizChangeSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计上下文：快照序列化、字段级差异、跳过与清理语义
 */
class BizChangeLogContextTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BizChangeLogContext context = new BizChangeLogContext(objectMapper);

    @AfterEach
    void tearDown() {
        context.clear();
    }

    @Test
    @DisplayName("只对变化的字段生成差异，未变化字段不出现")
    void diffsOnlyChangedFields() {
        Map<String, Object> before = Map.of("name", "湖大制度库", "embeddingModel", "m1", "deleted", 0);
        Map<String, Object> after = Map.of("name", "湖大制度库（2026）", "embeddingModel", "m1", "deleted", 0);

        var diff = context.diff(toNode(before), toNode(after));

        assertEquals(1, diff.size());
        assertEquals("/name", diff.get(0).get("field").asText());
        assertEquals("湖大制度库", diff.get(0).get("before").asText());
        assertEquals("湖大制度库（2026）", diff.get(0).get("after").asText());
    }

    @Test
    @DisplayName("嵌套对象与数组按 JSON Pointer 展开")
    void diffsNestedStructures() {
        Map<String, Object> before = Map.of("config", Map.of("topK", 5), "tags", List.of("a", "b"));
        Map<String, Object> after = Map.of("config", Map.of("topK", 9), "tags", List.of("a", "c"));

        var diff = context.diff(toNode(before), toNode(after));

        Map<String, String> changed = new LinkedHashMap<>();
        diff.forEach(item -> changed.put(item.get("field").asText(), item.get("after").asText()));
        assertEquals(Map.of("/config/topK", "9", "/tags/1", "c"), changed);
    }

    @Test
    @DisplayName("新增字段 before 为 null，删除字段 after 为 null")
    void diffsAddedAndRemovedFields() {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("name", "旧");
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", "旧");
        after.put("description", "新增说明");

        var diff = context.diff(toNode(before), toNode(after));

        assertEquals(1, diff.size());
        assertEquals("/description", diff.get(0).get("field").asText());
        assertTrue(diff.get(0).get("before").isNull());
        assertEquals("新增说明", diff.get(0).get("after").asText());
    }

    @Test
    @DisplayName("两侧完全相同与前后任一为空时的差异")
    void diffsIdenticalAndMissingSide() {
        Map<String, Object> same = Map.of("name", "同一个");
        assertTrue(context.diff(toNode(same), toNode(same)).isEmpty());

        var created = context.diff(null, toNode(Map.of("name", "新建")));
        assertEquals(1, created.size());
        assertEquals("/", created.get(0).get("field").asText());
        assertTrue(created.get(0).get("before").isNull());
        assertEquals("新建", created.get(0).get("after").get("name").asText());

        var deleted = context.diff(toNode(Map.of("name", "已删")), null);
        assertEquals(1, deleted.size());
        assertTrue(deleted.get(0).get("after").isNull());
    }

    @Test
    @DisplayName("put 立即固化快照，后续修改实体不影响已记录内容")
    void snapshotsAreFrozenAtPutTime() {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", "第一版");

        context.put("kb-1", null, after);
        after.put("name", "第二版");

        List<BizChangeSnapshot> snapshots = context.consume();
        assertEquals(1, snapshots.size());
        assertEquals("kb-1", snapshots.get(0).bizId());
        assertEquals("第一版", snapshots.get(0).afterSnapshot().get("name").asText());
    }

    @Test
    @DisplayName("同一方法可记录多条变更，consume 一次取走并清理")
    void collectsMultipleChangesAndClearsAfterConsume() {
        context.put("n1", null, Map.of("enabled", 1));
        context.put("n2", null, Map.of("enabled", 1));

        assertEquals(2, context.consume().size());
        assertTrue(context.consume().isEmpty(), "第二次取走应为空，说明上下文已清理");
    }

    @Test
    @DisplayName("skip 表示显式跳过记录，consume 返回 null")
    void skipMeansNoRecord() {
        context.put("kb-1", null, Map.of("name", "x"));
        context.skip();

        assertNull(context.consume());
    }

    private com.fasterxml.jackson.databind.JsonNode toNode(Object value) {
        return value == null ? null : objectMapper.valueToTree(value);
    }
}

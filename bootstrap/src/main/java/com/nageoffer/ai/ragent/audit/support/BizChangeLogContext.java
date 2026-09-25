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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;

/**
 * 业务变更审计上下文
 * <p>
 * 业务方法在写完数据后调用 {@link #put(String, Object, Object)} 提供变更前后快照，AOP 切面在方法结束时读取
 * 并落库。快照在 {@code put} 时立刻序列化并算出字段级差异，避免后续实体继续被修改污染已记录的快照。
 * <p>
 * 上下文是线程级的，切面在 finally 中调用 {@link #clear()}，防止线程池复用把上一次的快照带到下一次记录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BizChangeLogContext {

    private static final ThreadLocal<List<BizChangeSnapshot>> HOLDER = new ThreadLocal<>();

    private static final ThreadLocal<Boolean> SKIP_FLAG = new ThreadLocal<>();

    private final ObjectMapper objectMapper;

    /**
     * 记录一次变更；快照为 null 表示该侧不存在（新增只有 after，删除只有 before）
     * <p>
     * 同一方法内可多次调用（如批量操作逐条记录），切面会为每条记录写一行审计日志
     */
    public void put(String bizId, Object beforeSnapshot, Object afterSnapshot) {
        List<BizChangeSnapshot> snapshots = HOLDER.get();
        if (snapshots == null) {
            snapshots = new ArrayList<>();
            HOLDER.set(snapshots);
        }
        snapshots.add(new BizChangeSnapshot(bizId, buildPayload(beforeSnapshot, afterSnapshot)));
    }

    /**
     * 标记本次调用不记录（条件式埋点用）
     */
    public void skip() {
        SKIP_FLAG.set(Boolean.TRUE);
    }

    /**
     * 取走当前上下文并清理，供切面在方法结束时调用
     *
     * @return 本次记录的变更列表（可能为空，表示只记录操作本身）；{@code null} 表示显式跳过记录
     */
    public List<BizChangeSnapshot> consume() {
        Boolean skipped = SKIP_FLAG.get();
        SKIP_FLAG.remove();
        List<BizChangeSnapshot> snapshots = HOLDER.get();
        HOLDER.remove();
        if (Boolean.TRUE.equals(skipped)) {
            return null;
        }
        return snapshots == null ? List.of() : snapshots;
    }

    /**
     * 清理当前线程上下文
     */
    public void clear() {
        HOLDER.remove();
        SKIP_FLAG.remove();
    }

    private JsonNode buildPayload(Object beforeSnapshot, Object afterSnapshot) {
        JsonNode beforeNode = toJsonNode(beforeSnapshot);
        JsonNode afterNode = toJsonNode(afterSnapshot);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.set("beforeSnapshot", nullIfNullNode(beforeNode));
        payload.set("afterSnapshot", nullIfNullNode(afterNode));
        payload.set("changeDiff", diff(beforeNode, afterNode));
        return payload;
    }

    private JsonNode toJsonNode(Object value) {
        if (value == null) {
            return NullNode.getInstance();
        }
        return objectMapper.valueToTree(value);
    }

    private JsonNode nullIfNullNode(JsonNode node) {
        return node == null || node.isNull() ? NullNode.getInstance() : node;
    }

    /**
     * 递归比较两个 JSON，只保留真正变化的叶子节点
     */
    ArrayNode diff(JsonNode beforeNode, JsonNode afterNode) {
        ArrayNode result = JsonNodeFactory.instance.arrayNode();
        collectDiff("", beforeNode, afterNode, result);
        return result;
    }

    private void collectDiff(String path, JsonNode beforeNode, JsonNode afterNode, ArrayNode result) {
        JsonNode normalizedBefore = beforeNode == null ? NullNode.getInstance() : beforeNode;
        JsonNode normalizedAfter = afterNode == null ? NullNode.getInstance() : afterNode;
        if (Objects.equals(normalizedBefore, normalizedAfter)) {
            return;
        }

        if (normalizedBefore.isObject() && normalizedAfter.isObject()) {
            Set<String> fieldNames = new TreeSet<>();
            normalizedBefore.fieldNames().forEachRemaining(fieldNames::add);
            normalizedAfter.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                collectDiff(path + "/" + escapeJsonPointer(fieldName),
                        normalizedBefore.get(fieldName),
                        normalizedAfter.get(fieldName),
                        result);
            }
            return;
        }

        if (normalizedBefore.isArray() && normalizedAfter.isArray()) {
            int max = Math.max(normalizedBefore.size(), normalizedAfter.size());
            for (int i = 0; i < max; i++) {
                collectDiff(path + "/" + i,
                        i < normalizedBefore.size() ? normalizedBefore.get(i) : NullNode.getInstance(),
                        i < normalizedAfter.size() ? normalizedAfter.get(i) : NullNode.getInstance(),
                        result);
            }
            return;
        }

        ObjectNode item = JsonNodeFactory.instance.objectNode();
        item.put("field", path.isEmpty() ? "/" : path);
        item.set("before", normalizedBefore);
        item.set("after", normalizedAfter);
        result.add(item);
    }

    private String escapeJsonPointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    /**
     * 一次变更的上下文数据
     */
    public record BizChangeSnapshot(String bizId, JsonNode payload) {

        public JsonNode beforeSnapshot() {
            return read("beforeSnapshot");
        }

        public JsonNode afterSnapshot() {
            return read("afterSnapshot");
        }

        public JsonNode changeDiff() {
            return read("changeDiff");
        }

        private JsonNode read(String field) {
            if (payload == null) {
                return null;
            }
            JsonNode node = payload.get(field);
            return node == null || node.isNull() ? null : node;
        }
    }
}

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

package com.nageoffer.ai.ragent.rag.trace;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.dto.AgentRunResult;
import com.nageoffer.ai.ragent.agent.dto.AgentStep;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LangFuse 上报负载构造
 * <p>
 * 只做「本地 trace → LangFuse ingestion 批量事件」的纯映射，不碰网络：映射错了、字段缺了都能单测，
 * 上报失败也不会污染业务链路
 * <p>
 * 结构对齐 LangFuse Ingestion API：一次批量里先 {@code trace-create}，再若干 {@code span-create}
 * （span 用 {@code traceId} 挂到该 trace 上）
 */
@Component
@RequiredArgsConstructor
public class LangfusePayloadBuilder {

    /**
     * 一次 RAG 运行 → trace + 各节点 span
     */
    public Map<String, Object> forRagRun(RagTraceRunDO run, List<RagTraceNodeDO> nodes) {
        if (run == null || StrUtil.isBlank(run.getTraceId())) {
            return Map.of();
        }
        List<Map<String, Object>> batch = new ArrayList<>();
        batch.add(event("trace-create", "trace", Map.of(
                "id", run.getTraceId(),
                "name", StrUtil.blankToDefault(run.getTraceName(), "rag-chat"),
                "userId", StrUtil.nullToEmpty(run.getUserId()),
                "sessionId", StrUtil.nullToEmpty(run.getConversationId()),
                "timestamp", toInstant(run.getStartTime()),
                "release", "ragent",
                "input", StrUtil.nullToEmpty(run.getExtraData()),
                "metadata", metadata(Map.of(
                        "taskId", StrUtil.nullToEmpty(run.getTaskId()),
                        "entryMethod", StrUtil.nullToEmpty(run.getEntryMethod()),
                        "status", StrUtil.nullToEmpty(run.getStatus()),
                        "durationMs", run.getDurationMs() == null ? 0L : run.getDurationMs()
                )),
                "output", StrUtil.nullToEmpty(run.getErrorMessage())
        )));
        if (nodes != null) {
            for (RagTraceNodeDO node : nodes) {
                if (node == null || StrUtil.isBlank(node.getNodeId())) {
                    continue;
                }
                batch.add(event("span-create", "span", Map.of(
                        "id", node.getNodeId(),
                        "traceId", run.getTraceId(),
                        "parentObservationId", StrUtil.nullToEmpty(node.getParentNodeId()),
                        "name", StrUtil.blankToDefault(node.getNodeName(), node.getNodeId()),
                        "startTime", toInstant(node.getStartTime()),
                        "endTime", toInstant(node.getEndTime()),
                        "metadata", metadata(Map.of(
                                "nodeType", StrUtil.nullToEmpty(node.getNodeType()),
                                "className", StrUtil.nullToEmpty(node.getClassName()),
                                "methodName", StrUtil.nullToEmpty(node.getMethodName()),
                                "status", StrUtil.nullToEmpty(node.getStatus()),
                                "durationMs", node.getDurationMs() == null ? 0L : node.getDurationMs(),
                                "error", StrUtil.nullToEmpty(node.getErrorMessage())
                        ))
                )));
            }
        }
        return Map.of("batch", batch);
    }

    /**
     * 一次 Agent 运行 → trace + 每个工具步骤 span
     */
    public Map<String, Object> forAgentRun(String conversationId, String question, AgentRunResult result) {
        if (result == null) {
            return Map.of();
        }
        String traceId = "agent-" + StrUtil.blankToDefault(conversationId, "anonymous") + "-" + System.nanoTime();
        List<Map<String, Object>> batch = new ArrayList<>();
        batch.add(event("trace-create", "trace", Map.of(
                "id", traceId,
                "name", "agent-chat",
                "sessionId", StrUtil.nullToEmpty(conversationId),
                "timestamp", Instant.now().toString(),
                "release", "ragent",
                "input", StrUtil.nullToEmpty(question),
                "output", StrUtil.nullToEmpty(result.answer()),
                "metadata", metadata(Map.of(
                        "stopReason", result.stopReason() == null ? "" : result.stopReason().name(),
                        "elapsedMs", result.elapsedMs(),
                        "steps", result.steps().size()
                ))
        )));
        int index = 0;
        for (AgentStep step : result.steps()) {
            index++;
            if (!step.hasToolCall()) {
                continue;
            }
            batch.add(event("span-create", "span", Map.of(
                    "id", traceId + "-step-" + index,
                    "traceId", traceId,
                    "name", step.toolId(),
                    "startTime", Instant.now().toString(),
                    "metadata", metadata(Map.of(
                            "thought", StrUtil.nullToEmpty(step.thought()),
                            "arguments", step.arguments(),
                            "observation", StrUtil.nullToEmpty(step.observation()),
                            "latencyMs", step.latencyMs()
                    ))
            )));
        }
        return Map.of("batch", batch);
    }

    private Map<String, Object> event(String type, String bodyKey, Map<String, Object> body) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", java.util.UUID.randomUUID().toString());
        event.put("type", type);
        event.put("timestamp", Instant.now().toString());
        event.put("body", body);
        return Map.of("type", type, "id", event.get("id"), "timestamp", event.get("timestamp"), bodyKey, body);
    }

    private Map<String, Object> metadata(Map<String, Object> raw) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value != null) {
                metadata.put(key, value);
            }
        });
        return metadata;
    }

    private String toInstant(java.util.Date date) {
        return date == null ? Instant.now().toString() : date.toInstant().toString();
    }
}

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

import com.nageoffer.ai.ragent.agent.dto.AgentRunResult;
import com.nageoffer.ai.ragent.agent.dto.AgentStep;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LangFuse 上报负载：trace + span 结构、ID 关联、空输入保护
 */
class LangfusePayloadBuilderTest {

    private final LangfusePayloadBuilder builder = new LangfusePayloadBuilder();

    @Test
    @DisplayName("RAG 运行映射为 trace-create + 逐节点 span-create")
    void buildsRagRunBatch() {
        RagTraceRunDO run = RagTraceRunDO.builder()
                .traceId("trace-1")
                .traceName("rag-chat")
                .conversationId("conv-1")
                .taskId("task-1")
                .userId("u-1")
                .status("SUCCESS")
                .startTime(new Date(1000))
                .endTime(new Date(3000))
                .durationMs(2000L)
                .build();
        RagTraceNodeDO node = RagTraceNodeDO.builder()
                .traceId("trace-1")
                .nodeId("node-1")
                .parentNodeId(null)
                .nodeType("RETRIEVE")
                .nodeName("retrieve")
                .className("RetrievalEngine")
                .methodName("retrieve")
                .status("SUCCESS")
                .startTime(new Date(1100))
                .endTime(new Date(1500))
                .durationMs(400L)
                .build();

        Map<String, Object> payload = builder.forRagRun(run, List.of(node));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> batch = (List<Map<String, Object>>) payload.get("batch");
        assertEquals(2, batch.size());
        assertEquals("trace-create", batch.get(0).get("type"));
        assertEquals("span-create", batch.get(1).get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> trace = (Map<String, Object>) batch.get(0).get("trace");
        assertEquals("trace-1", trace.get("id"));
        assertEquals("conv-1", trace.get("sessionId"));
        assertEquals("u-1", trace.get("userId"));

        @SuppressWarnings("unchecked")
        Map<String, Object> span = (Map<String, Object>) batch.get(1).get("span");
        assertEquals("node-1", span.get("id"));
        assertEquals("trace-1", span.get("traceId"), "span 必须挂到本次 trace 上");
        assertEquals("retrieve", span.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) span.get("metadata");
        assertEquals("RetrievalEngine", metadata.get("className"));
    }

    @Test
    @DisplayName("缺 traceId 或空输入时返回空负载，不产生无效事件")
    void guardsInvalidInput() {
        assertTrue(builder.forRagRun(null, List.of()).isEmpty());
        assertTrue(builder.forRagRun(RagTraceRunDO.builder().build(), List.of()).isEmpty());
        assertTrue(builder.forAgentRun("conv", "问题", null).isEmpty());
    }

    @Test
    @DisplayName("Agent 运行：trace 带结束原因，工具步骤各一条 span，纯思考步不产生 span")
    void buildsAgentRunBatch() {
        AgentRunResult result = new AgentRunResult("最终回答", List.of(
                new AgentStep(1, "先查资料", "knowledge_search", Map.of("query", "转专业"), "命中 1 条", 12L),
                new AgentStep(2, "收口", null, Map.of(), null, 3L)
        ), AgentRunResult.StopReason.FINAL_ANSWER, 42L);

        Map<String, Object> payload = builder.forAgentRun("conv-9", "转专业需要什么材料", result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> batch = (List<Map<String, Object>>) payload.get("batch");
        assertEquals(2, batch.size(), "1 条 trace + 1 条工具 span");

        @SuppressWarnings("unchecked")
        Map<String, Object> trace = (Map<String, Object>) batch.get(0).get("trace");
        assertEquals("agent-chat", trace.get("name"));
        assertEquals("conv-9", trace.get("sessionId"));
        assertEquals("转专业需要什么材料", trace.get("input"));
        assertEquals("最终回答", trace.get("output"));

        @SuppressWarnings("unchecked")
        Map<String, Object> span = (Map<String, Object>) batch.get(1).get("span");
        assertEquals("knowledge_search", span.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) span.get("metadata");
        assertEquals("命中 1 条", metadata.get("observation"));
        assertEquals(12L, metadata.get("latencyMs"));
        assertFalse(span.get("id").toString().isBlank());
    }
}

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

package com.nageoffer.ai.ragent.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentRunResult;
import com.nageoffer.ai.ragent.agent.dto.AgentStep;
import com.nageoffer.ai.ragent.agent.dto.AgentStreamEvent;
import com.nageoffer.ai.ragent.agent.engine.AgentEngine;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.AgentMemoryService;
import com.nageoffer.ai.ragent.agent.skill.AgentSkillService;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Agent 事件组装：事件顺序、工具事件与消息块
 */
class AgentChatServiceImplTest {

    private final AgentChatServiceImpl service = new AgentChatServiceImpl(
            mock(AgentEngine.class), mock(AgentConversationService.class), mock(AgentMemoryService.class),
            mock(AgentSkillService.class), mock(AgentToolCatalog.class), new ObjectMapper());

    @Test
    @DisplayName("事件顺序：meta → tool → message → finish")
    void assemblesEventsInOrder() {
        AgentRunResult result = new AgentRunResult("最终回答", List.of(
                new AgentStep(1, "先查资料", "knowledge_search", Map.of("query", "转专业"), "命中 1 条", 12L),
                new AgentStep(2, "信息够了", null, Map.of(), null, 5L)
        ), AgentRunResult.StopReason.FINAL_ANSWER, 42L);

        List<AgentStreamEvent> events = service.assembleEvents("conv-1", result);

        assertEquals(List.of("meta", "tool", "message", "finish"),
                events.stream().map(AgentStreamEvent::event).toList());

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) events.get(0).data();
        assertEquals("conv-1", meta.get("conversationId"));
        assertEquals("FINAL_ANSWER", meta.get("stopReason"));

        @SuppressWarnings("unchecked")
        Map<String, Object> tool = (Map<String, Object>) events.get(1).data();
        assertEquals("knowledge_search", tool.get("toolId"));
        assertTrue(tool.containsKey("arguments"));

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) events.get(2).data();
        assertEquals("最终回答", message.get("content"));
    }

    @Test
    @DisplayName("达到步数上限时补一条 hint")
    void addsHintOnMaxSteps() {
        AgentRunResult result = new AgentRunResult("兜底回答", List.of(
                new AgentStep(1, null, "knowledge_search", Map.of(), "观察", 10L)
        ), AgentRunResult.StopReason.MAX_STEPS, 30L);

        List<AgentStreamEvent> events = service.assembleEvents("conv-2", result);

        assertEquals(List.of("meta", "tool", "hint", "message", "finish"),
                events.stream().map(AgentStreamEvent::event).toList());
    }

    @Test
    @DisplayName("消息块只保留工具步骤；没有工具调用时不写块")
    void buildsToolBlocks() {
        String blocks = service.toBlocksJson(List.of(
                new AgentStep(1, "思考", "knowledge_search", Map.of("query", "x"), "观察", 3L),
                new AgentStep(2, "收口", null, Map.of(), null, 1L)
        ));

        assertTrue(blocks.contains("\"type\":\"tool\""), blocks);
        assertTrue(blocks.contains("\"toolId\":\"knowledge_search\""), blocks);
        assertTrue(blocks.contains("\"observation\":\"观察\""), blocks);

        assertNull(service.toBlocksJson(List.of(new AgentStep(1, "收口", null, Map.of(), null, 1L))));
    }

    @Test
    @DisplayName("无工具步骤时只下发 meta → message → finish")
    void skipsToolEventsWhenNoToolCall() {
        AgentRunResult result = new AgentRunResult("直接回答", List.of(
                new AgentStep(1, null, null, Map.of(), null, 2L)
        ), AgentRunResult.StopReason.FALLBACK_TEXT, 2L);

        List<AgentStreamEvent> events = service.assembleEvents("conv-3", result);

        assertEquals(List.of("meta", "message", "finish"),
                events.stream().map(AgentStreamEvent::event).toList());
    }
}

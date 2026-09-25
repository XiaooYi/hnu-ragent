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
import com.nageoffer.ai.ragent.agent.dto.AgentRequest;
import com.nageoffer.ai.ragent.agent.dto.AgentRunResult;
import com.nageoffer.ai.ragent.agent.dto.AgentStep;
import com.nageoffer.ai.ragent.agent.dto.AgentStreamEvent;
import com.nageoffer.ai.ragent.agent.engine.AgentEngine;
import com.nageoffer.ai.ragent.agent.enums.AgentSSEEventType;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.framework.cancellation.TaskCancellation;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 对话实现
 * <p>
 * 事件顺序：meta → 每个工具步骤一组 tool(start/end) → message(最终回答) → finish；
 * 达到步数上限时额外补一条 hint。组装与下发分离：{@link #assembleEvents} 是纯函数，可脱离 SSE 单测
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.agent", name = "enabled", havingValue = "true")
public class AgentChatServiceImpl implements AgentChatService {

    private final AgentEngine agentEngine;
    private final AgentConversationService conversationService;
    private final ObjectMapper objectMapper;

    @Override
    public void streamChat(String question, String conversationId, SseEmitter emitter) {
        SseEmitterSender sender = new SseEmitterSender(emitter);
        long startTime = System.currentTimeMillis();
        String resolvedConversationId = null;
        String userMessageId = null;
        try {
            resolvedConversationId = conversationService.ensureConversation(conversationId, question);
            userMessageId = conversationService.saveUserMessage(resolvedConversationId, question);

            AgentRunResult result = agentEngine.run(AgentRequest.builder()
                    .question(question)
                    .conversationId(resolvedConversationId)
                    .build());

            for (AgentStreamEvent event : assembleEvents(resolvedConversationId, result)) {
                sender.sendEvent(event.event(), event.data());
            }

            conversationService.saveAssistantMessage(resolvedConversationId, userMessageId, result.answer(),
                    toBlocksJson(result.steps()), "NORMAL", result.elapsedMs());
            sender.complete();
        } catch (Exception e) {
            if (TaskCancellation.isCancellation(e)) {
                log.info("Agent 对话被用户取消, conversationId={}", resolvedConversationId);
                if (resolvedConversationId != null) {
                    conversationService.saveAssistantMessage(resolvedConversationId, userMessageId, "",
                            null, "INTERRUPTED", System.currentTimeMillis() - startTime);
                }
                sender.sendEvent(AgentSSEEventType.FINISH.getValue(), Map.of("reason", "INTERRUPTED"));
                sender.complete();
                return;
            }
            log.error("Agent 对话失败, conversationId={}", resolvedConversationId, e);
            if (resolvedConversationId != null) {
                conversationService.saveAssistantMessage(resolvedConversationId, userMessageId,
                        "抱歉，本次回答失败，请稍后重试。", null, "FAILED", System.currentTimeMillis() - startTime);
            }
            sender.sendEvent(AgentSSEEventType.MESSAGE.getValue(),
                    Map.of("content", "抱歉，本次回答失败，请稍后重试。"));
            sender.sendEvent(AgentSSEEventType.FINISH.getValue(), Map.of("reason", "FAILED"));
            sender.complete();
        }
    }

    /**
     * 组装一轮运行要下发的事件（纯函数，便于单测）
     */
    List<AgentStreamEvent> assembleEvents(String conversationId, AgentRunResult result) {
        List<AgentStreamEvent> events = new ArrayList<>();
        events.add(new AgentStreamEvent(AgentSSEEventType.META.getValue(), Map.of(
                "conversationId", conversationId,
                "stopReason", result.stopReason().name(),
                "elapsedMs", result.elapsedMs()
        )));

        int index = 0;
        for (AgentStep step : result.steps()) {
            index++;
            if (!step.hasToolCall()) {
                continue;
            }
            events.add(new AgentStreamEvent(AgentSSEEventType.TOOL.getValue(), Map.of(
                    "step", index,
                    "toolId", step.toolId(),
                    "arguments", step.arguments(),
                    "latencyMs", step.latencyMs()
            )));
        }

        if (result.stopReason() == AgentRunResult.StopReason.MAX_STEPS) {
            events.add(new AgentStreamEvent(AgentSSEEventType.HINT.getValue(),
                    Map.of("message", "已达到单轮工具调用上限，本次基于已有信息作答")));
        }

        events.add(new AgentStreamEvent(AgentSSEEventType.MESSAGE.getValue(),
                Map.of("content", result.answer())));
        events.add(new AgentStreamEvent(AgentSSEEventType.FINISH.getValue(), Map.of(
                "reason", result.stopReason().name(),
                "elapsedMs", result.elapsedMs()
        )));
        return events;
    }

    /**
     * 工具步骤序列化为消息块；无工具调用返回 null（避免写空数组）
     */
    String toBlocksJson(List<AgentStep> steps) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        int index = 0;
        for (AgentStep step : steps) {
            index++;
            if (!step.hasToolCall()) {
                continue;
            }
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool");
            block.put("step", index);
            block.put("toolId", step.toolId());
            block.put("thought", step.thought());
            block.put("arguments", step.arguments());
            block.put("observation", step.observation());
            block.put("latencyMs", step.latencyMs());
            blocks.add(block);
        }
        if (blocks.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(blocks);
        } catch (Exception e) {
            log.warn("Agent 工具块序列化失败，已跳过", e);
            return null;
        }
    }
}

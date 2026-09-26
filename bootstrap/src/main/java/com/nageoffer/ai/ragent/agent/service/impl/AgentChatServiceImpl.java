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
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.engine.AgentEngine;
import com.nageoffer.ai.ragent.agent.enums.AgentSSEEventType;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.AgentHistoryAssembler;
import com.nageoffer.ai.ragent.agent.service.AgentMemoryService;
import com.nageoffer.ai.ragent.agent.skill.AgentSkill;
import com.nageoffer.ai.ragent.agent.skill.AgentSkillService;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.cancellation.TaskCancellation;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.rag.trace.LangfuseReporter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
    private final AgentProperties agentProperties;
    private final AgentConversationService conversationService;
    private final AgentHistoryAssembler agentHistoryAssembler;
    private final AgentMemoryService agentMemoryService;
    private final AgentSkillService agentSkillService;
    private final AgentToolCatalog agentToolCatalog;
    private final ObjectProvider<LangfuseReporter> langfuseReporterProvider;
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

            List<String> memories = agentMemoryService.recall(question);
            List<String> skills = agentSkillService.match(question, 0).stream()
                    .map(AgentSkill::render)
                    .toList();
            String history = resolveHistory(resolvedConversationId);
            AgentRunResult result = agentEngine.run(AgentRequest.builder()
                    .question(question)
                    .conversationId(resolvedConversationId)
                    .memories(memories)
                    .skills(skills)
                    .history(history)
                    .build());

            for (AgentStreamEvent event : assembleEvents(resolvedConversationId, result)) {
                sender.sendEvent(event.event(), event.data());
            }

            conversationService.saveAssistantMessage(resolvedConversationId, userMessageId, result.answer(),
                    toBlocksJson(result.steps()),
                    result.stopReason() == AgentRunResult.StopReason.CONFIRM_REQUIRED ? "CONFIRM_PENDING" : "NORMAL",
                    result.elapsedMs());
            if (result.stopReason() != AgentRunResult.StopReason.CONFIRM_REQUIRED) {
                // 待确认的那一轮没有真实回答，不值得沉淀记忆
                rememberQuietly(question, result.answer());
            }
            reportToLangfuse(resolvedConversationId, question, result);
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
     * 用户确认后执行写操作
     */
    @Override
    public String executeConfirmed(String toolId, Map<String, Object> arguments) {
        if (toolId == null || toolId.isBlank()) {
            throw new ClientException("待确认的工具不能为空");
        }
        boolean available = agentToolCatalog.listTools().stream()
                .anyMatch(tool -> tool.id().equals(toolId));
        if (!available) {
            throw new ClientException("工具不存在或未启用：" + toolId);
        }
        log.info("用户已确认写操作，开始执行, toolId={}", toolId);
        return agentToolCatalog.execute(toolId, arguments);
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

        if (result.pendingCall() != null) {
            events.add(new AgentStreamEvent(AgentSSEEventType.CONFIRM.getValue(), Map.of(
                    "toolId", result.pendingCall().toolId(),
                    "arguments", result.pendingCall().arguments(),
                    "fieldLabels", result.pendingCall().fieldLabels(),
                    "stepIndex", result.pendingCall().stepIndex()
            )));
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

    /**
     * 记忆沉淀 best-effort：写记忆失败不能影响已经答完的这一轮
     */
    private void rememberQuietly(String question, String answer) {
        try {
            agentMemoryService.remember(question, answer);
        } catch (Exception e) {
            log.warn("Agent 记忆沉淀失败，已跳过", e);
        }
    }

    /**
     * 装配历史（含压缩）：本轮提问已落库，装配前要把它排除，否则模型会把当前问题当成历史
     */
    private String resolveHistory(String conversationId) {
        if (!Boolean.TRUE.equals(agentProperties.getHistory().getEnabled())) {
            return "";
        }
        try {
            List<AgentMessageDO> messages = conversationService.listMessages(conversationId);
            if (messages == null || messages.isEmpty()) {
                return "";
            }
            // 去掉最后一条（就是本次提问）
            List<AgentMessageDO> history = messages.subList(0, messages.size() - 1);
            return agentHistoryAssembler.assemble(history);
        } catch (Exception e) {
            log.warn("装配 Agent 历史失败，本轮按无历史处理, conversationId={}", conversationId, e);
            return "";
        }
    }

    /**
     * 上报 Agent 运行到 LangFuse（未启用时是空操作）
     */
    private void reportToLangfuse(String conversationId, String question, AgentRunResult result) {
        LangfuseReporter reporter = langfuseReporterProvider.getIfAvailable();
        if (reporter == null) {
            return;
        }
        try {
            reporter.reportAgentRun(conversationId, question, result);
        } catch (Exception e) {
            log.warn("Agent 运行上报 LangFuse 失败（不影响业务）, conversationId={}", conversationId, e);
        }
    }
}

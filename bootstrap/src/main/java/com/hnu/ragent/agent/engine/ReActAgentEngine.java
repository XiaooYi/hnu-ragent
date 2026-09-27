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

package com.hnu.ragent.agent.engine;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnu.ragent.agent.config.AgentProperties;
import com.hnu.ragent.agent.dto.AgentRequest;
import com.hnu.ragent.agent.dto.AgentRunResult;
import com.hnu.ragent.agent.dto.AgentStep;
import com.hnu.ragent.agent.tool.AgentToolCatalog;
import com.hnu.ragent.framework.cancellation.TaskCancellation;
import com.hnu.ragent.framework.convention.ChatMessage;
import com.hnu.ragent.framework.convention.ChatRequest;
import com.hnu.ragent.infra.chat.LLMService;
import com.hnu.ragent.infra.enums.Tier;
import com.hnu.ragent.infra.util.LLMResponseCleaner;
import com.hnu.ragent.rag.constant.RAGConstant;
import com.hnu.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * 自研最小 ReAct 引擎
 * <p>
 * 循环：想（think）→ 调工具（act）→ 看结果（observe）→ 再想，直到模型给出最终回答或达到步数上限。
 * 只在 {@code ai.agent.enabled=true} 时注册；协议解析失败视为「模型已给出回答」而不是报错——
 * Agent 不能因为格式抖一下就整体失败
 * <p>
 * 阈值与提示词见 docs/upstream/features/up-32-agent-runtime.md
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.agent", name = "enabled", havingValue = "true")
public class ReActAgentEngine implements AgentEngine {

    private final AgentProperties agentProperties;
    private final AgentToolCatalog toolCatalog;
    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ObjectMapper objectMapper;

    @Override
    public AgentRunResult run(AgentRequest request) {
        long startTime = System.currentTimeMillis();
        int maxSteps = request.resolveMaxSteps(agentProperties.getMaxSteps());
        List<AgentStep> steps = new ArrayList<>();
        List<String> observations = new ArrayList<>();
        String lastObservation = null;

        for (int index = 1; index <= maxSteps; index++) {
            if (TaskCancellation.isCancelled()) {
                log.info("Agent 运行被用户取消, conversationId={}", request.conversationId());
                throw new CancellationException("Agent run cancelled");
            }
            long stepStart = System.currentTimeMillis();
            String raw = callModel(request, observations);
            JsonNode node = parseNode(raw);
            if (node == null) {
                // 协议不合规：整段文本当作最终回答，不因为格式问题让整轮失败
                String answer = StrUtil.blankToDefault(raw, "抱歉，我暂时无法回答这个问题。");
                steps.add(new AgentStep(index, null, null, Map.of(), null,
                        System.currentTimeMillis() - stepStart));
                log.info("Agent 输出非 JSON，按最终回答处理, conversationId={}", request.conversationId());
                return new AgentRunResult(answer, steps, AgentRunResult.StopReason.FALLBACK_TEXT,
                        System.currentTimeMillis() - startTime);
            }

            String thought = node.path("thought").asText("");
            String finalAnswer = node.path("final").asText("");
            if (StrUtil.isNotBlank(finalAnswer)) {
                steps.add(new AgentStep(index, thought, null, Map.of(), null,
                        System.currentTimeMillis() - stepStart));
                return new AgentRunResult(finalAnswer, steps, AgentRunResult.StopReason.FINAL_ANSWER,
                        System.currentTimeMillis() - startTime);
            }

            JsonNode action = node.path("action");
            String toolId = action.path("tool").asText("");
            Map<String, Object> arguments = readArguments(action.path("arguments"));
            if (StrUtil.isBlank(toolId)) {
                // 既没有 final 也没有 action：把这一步当成收口失败，回灌提示继续
                String observation = "输出缺少 final 或 action，请按协议重新输出";
                observations.add(observation);
                lastObservation = observation;
                steps.add(new AgentStep(index, thought, null, Map.of(), observation,
                        System.currentTimeMillis() - stepStart));
                continue;
            }

            // 写操作默认拒绝：先交给用户确认，绝不替用户做主
            if (toolCatalog.requiresConfirmation(toolId)) {
                String answer = "这个操作会修改数据，需要你确认后才会执行：" + toolId;
                steps.add(new AgentStep(index, thought, toolId, arguments, answer,
                        System.currentTimeMillis() - stepStart));
                log.info("Agent 命中写操作，等待用户确认, toolId={}, conversationId={}",
                        toolId, request.conversationId());
                return new AgentRunResult(answer, steps, AgentRunResult.StopReason.CONFIRM_REQUIRED,
                        System.currentTimeMillis() - startTime,
                        new AgentRunResult.PendingToolCall(toolId, arguments,
                                toolCatalog.describeArguments(toolId, arguments), index));
            }

            String observation = executeTool(toolId, arguments);
            lastObservation = observation;
            observations.add("第 " + index + " 步调用 " + toolId + " 的观察结果：\n" + observation);
            steps.add(new AgentStep(index, thought, toolId, arguments, observation,
                    System.currentTimeMillis() - stepStart));
        }

        // 步数用尽：用最后一次观察收口，保证有可读输出
        String answer = StrUtil.isNotBlank(lastObservation)
                ? "我已经查到这里，信息可能还不完整：\n" + lastObservation
                : "抱歉，我没能在限定步数内找到足够信息，请补充更具体的问题。";
        log.warn("Agent 达到步数上限, maxSteps={}, conversationId={}", maxSteps, request.conversationId());
        return new AgentRunResult(answer, steps, AgentRunResult.StopReason.MAX_STEPS,
                System.currentTimeMillis() - startTime);
    }

    private String callModel(AgentRequest request, List<String> observations) {
        String prompt = promptTemplateLoader.render(
                RAGConstant.AGENT_REACT_PROMPT_PATH,
                Map.of(
                        "tools", toolCatalog.describeForPrompt(),
                        "memories", renderMemories(request.memories()),
                        "skills", renderSkills(request.skills()),
                        "history", StrUtil.blankToDefault(request.history(), "（本次是新会话的第一轮）"),
                        "question", request.question(),
                        "observations", observations.isEmpty()
                                ? "（还没有观察结果）"
                                : "已获得的观察结果：\n" + String.join("\n\n", observations)
                )
        );
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(agentProperties.getTemperature())
                .topP(agentProperties.getTopP())
                .thinking(false)
                .build();
        return llmService.chat(chatRequest, Tier.STANDARD);
    }

    /**
     * 记忆块渲染：没有记忆时给一句「暂无」，避免提示词里出现空段落让模型误解
     */
    private String renderMemories(List<String> memories) {
        if (memories == null || memories.isEmpty()) {
            return "（暂无）";
        }
        return memories.stream()
                .filter(memory -> memory != null && !memory.isBlank())
                .map(memory -> "- " + memory.trim())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /**
     * 技能块渲染：命中的手册逐份给出，没有命中时明确「无」，避免模型把空段落当约束
     */
    private String renderSkills(List<String> skills) {
        if (skills == null || skills.isEmpty()) {
            return "（本次没有命中技能手册）";
        }
        return skills.stream()
                .filter(skill -> skill != null && !skill.isBlank())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    /**
     * 解析模型输出；带 markdown 代码围栏时先剥离，解析失败返回 null
     */
    private JsonNode parseNode(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
            JsonNode node = objectMapper.readTree(cleaned);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> readArguments(JsonNode arguments) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (arguments != null && arguments.isObject()) {
            arguments.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                result.put(entry.getKey(), value.isValueNode() ? value.asText() : value.toString());
            });
        }
        return result;
    }

    /**
     * 执行工具：异常与未知工具都转成观察结果回灌给模型，由模型决定下一步
     */
    private String executeTool(String toolId, Map<String, Object> arguments) {
        try {
            String observation = toolCatalog.execute(toolId, arguments);
            return truncate(observation);
        } catch (Exception e) {
            log.warn("Agent 工具执行失败, toolId={}", toolId, e);
            return truncate("工具执行失败: " + e.getMessage());
        }
    }

    private String truncate(String observation) {
        if (observation == null) {
            return "工具没有返回内容";
        }
        int limit = agentProperties.getMaxObservationChars();
        return observation.length() > limit ? observation.substring(0, limit) + "…" : observation;
    }
}

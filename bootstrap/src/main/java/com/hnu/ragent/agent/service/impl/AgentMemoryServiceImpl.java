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

package com.hnu.ragent.agent.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnu.ragent.agent.config.AgentProperties;
import com.hnu.ragent.agent.dao.entity.AgentMemoryDO;
import com.hnu.ragent.agent.dao.mapper.AgentMemoryMapper;
import com.hnu.ragent.agent.service.AgentMemoryService;
import com.hnu.ragent.framework.context.UserContext;
import com.hnu.ragent.framework.convention.ChatMessage;
import com.hnu.ragent.framework.convention.ChatRequest;
import com.hnu.ragent.infra.chat.LLMService;
import com.hnu.ragent.infra.enums.Tier;
import com.hnu.ragent.infra.util.LLMResponseCleaner;
import com.hnu.ragent.rag.constant.RAGConstant;
import com.hnu.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 长期记忆实现
 * <p>
 * 召回用「字符重合度 + 时间新近度」排序，不引入向量：记忆条数少（几十条量级），不值得为它增加一条
 * embedding 依赖与一致性负担；条数增长后可平滑替换为向量召回
 * <p>
 * 抽取用 FAST 档：这是高频小任务，判错可降级（宁可少记，不可记错）；整段解析失败时**不写任何记忆**
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryServiceImpl implements AgentMemoryService {

    private static final int MAX_CONTENT_LENGTH = 500;

    private final AgentMemoryMapper memoryMapper;
    private final AgentProperties agentProperties;
    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ObjectMapper objectMapper;

    @Override
    public List<String> recall(String question) {
        if (!enabled()) {
            return List.of();
        }
        List<AgentMemoryDO> active = listActive();
        if (active.isEmpty()) {
            return List.of();
        }
        Set<String> questionChars = charSet(question);
        int limit = agentProperties.getMemory().getRecallLimit();
        return active.stream()
                .sorted(Comparator
                        .comparingInt((AgentMemoryDO memory) -> -overlap(questionChars, memory.getContent()))
                        .thenComparing(AgentMemoryDO::getCreateTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .map(AgentMemoryDO::getContent)
                .toList();
    }

    @Override
    public int remember(String question, String answer) {
        if (!enabled() || StrUtil.isBlank(question) || StrUtil.isBlank(answer)) {
            return 0;
        }
        String userId = currentUserId();
        List<AgentMemoryDO> active = listActive();
        List<AgentMemoryDO> facts = extract(question, answer, active);
        int inserted = 0;
        for (AgentMemoryDO fact : facts) {
            // 相同事实不重复写：记忆表里出现两条同义事实，后续召回会互相挤占名额
            boolean duplicated = active.stream()
                    .anyMatch(memory -> memory.getContent().equals(fact.getContent()));
            if (duplicated) {
                continue;
            }
            // supersededBy 在抽取阶段临时承载「被取代记忆的原文」，落库前换算成真正的新记忆 ID
            AgentMemoryDO superseded = StrUtil.isBlank(fact.getSupersededBy())
                    ? null
                    : active.stream()
                      .filter(memory -> memory.getContent().equals(fact.getSupersededBy()))
                      .findFirst()
                      .orElse(null);
            fact.setUserId(userId);
            fact.setSupersededBy(null);
            memoryMapper.insert(fact);
            inserted++;
            if (superseded != null) {
                fact.setSupersededBy(null);
                superseded.setInvalidAt(new Date());
                superseded.setSupersededBy(fact.getId());
                memoryMapper.updateById(superseded);
            }
        }
        if (inserted > 0) {
            log.info("Agent 记忆新增 {} 条, userId={}", inserted, userId);
        }
        return inserted;
    }

    @Override
    public List<AgentMemoryDO> listActive() {
        return memoryMapper.selectList(Wrappers.lambdaQuery(AgentMemoryDO.class)
                .eq(AgentMemoryDO::getUserId, currentUserId())
                .isNull(AgentMemoryDO::getInvalidAt)
                .orderByDesc(AgentMemoryDO::getCreateTime));
    }

    @Override
    public void invalidate(String memoryId) {
        AgentMemoryDO memory = memoryMapper.selectById(memoryId);
        if (memory == null || !currentUserId().equals(memory.getUserId()) || memory.getInvalidAt() != null) {
            return;
        }
        memory.setInvalidAt(new Date());
        memoryMapper.updateById(memory);
    }

    /**
     * 调模型抽取事实；解析失败或没有事实时返回空列表
     */
    List<AgentMemoryDO> extract(String question, String answer, List<AgentMemoryDO> active) {
        String memories = active.isEmpty()
                ? "（暂无）"
                : String.join("\n", active.stream().map(memory -> "- " + memory.getContent()).toList());
        String prompt = promptTemplateLoader.render(
                RAGConstant.AGENT_MEMORY_EXTRACT_PROMPT_PATH,
                Map.of("question", question, "answer", answer, "memories", memories)
        );
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();
        String raw;
        try {
            raw = llmService.chat(request, Tier.FAST);
        } catch (Exception e) {
            log.warn("记忆抽取调用失败，本轮不沉淀记忆", e);
            return List.of();
        }
        return parseFacts(raw);
    }

    List<AgentMemoryDO> parseFacts(String raw) {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        JsonNode facts;
        try {
            facts = objectMapper.readTree(LLMResponseCleaner.stripMarkdownCodeFence(raw)).path("facts");
        } catch (Exception e) {
            log.warn("记忆抽取返回非法 JSON，本轮不沉淀记忆: {}", raw);
            return List.of();
        }
        if (!facts.isArray()) {
            return List.of();
        }
        int maxFacts = agentProperties.getMemory().getMaxFactsPerTurn();
        List<AgentMemoryDO> result = new ArrayList<>();
        for (JsonNode node : facts) {
            String content = StrUtil.trimToNull(node.path("content").asText(""));
            if (content == null) {
                continue;
            }
            if (content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH);
            }
            String replaces = StrUtil.trimToNull(node.path("replaces").asText(""));
            result.add(AgentMemoryDO.builder()
                    .content(content)
                    .sourceType(normalizeType(node.path("type").asText("")))
                    .supersededBy(replaces)
                    .build());
            if (result.size() >= maxFacts) {
                break;
            }
        }
        return result;
    }

    private String normalizeType(String type) {
        String normalized = StrUtil.blankToDefault(type, "FACT").trim().toUpperCase();
        return switch (normalized) {
            case "PREFERENCE", "FACT", "CONTEXT" -> normalized;
            default -> "FACT";
        };
    }

    private int overlap(Set<String> questionChars, String content) {
        if (StrUtil.isBlank(content) || questionChars.isEmpty()) {
            return 0;
        }
        Set<String> contentChars = charSet(content);
        contentChars.retainAll(questionChars);
        return contentChars.size();
    }

    private Set<String> charSet(String text) {
        Set<String> chars = new HashSet<>();
        if (StrUtil.isBlank(text)) {
            return chars;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                chars.add(String.valueOf(c));
            }
        }
        return chars;
    }

    private boolean enabled() {
        return Boolean.TRUE.equals(agentProperties.getMemory().getEnabled());
    }

    private String currentUserId() {
        return StrUtil.blankToDefault(UserContext.getUserId(), "anonymous");
    }
}

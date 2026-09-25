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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 设置接口暴露档位配置：chat 组带档位，其余模型组不带
 */
class RAGSettingsControllerTest {

    private AIModelProperties aiModelProperties;
    private RAGSettingsController controller;

    @BeforeEach
    void setUp() {
        aiModelProperties = new AIModelProperties();
        controller = new RAGSettingsController(
                new RAGDefaultProperties(),
                new RAGConfigProperties(),
                new RAGRateLimitProperties(),
                new MemoryProperties(),
                aiModelProperties);
        ReflectionTestUtils.setField(controller, "maxFileSize", DataSize.ofMegabytes(50));
        ReflectionTestUtils.setField(controller, "maxRequestSize", DataSize.ofMegabytes(100));
    }

    @Test
    @DisplayName("chat 组暴露档位名、超时预算与有序候选")
    void exposesChatTiers() {
        AIModelProperties.ModelGroup chat = aiModelProperties.getChat();
        chat.setDefaultTier("standard");
        chat.setDeepThinkingTier("deep");
        Map<String, AIModelProperties.TierConfig> tiers = new LinkedHashMap<>();
        tiers.put("fast", tier(List.of("qwen3-local", "qwen-plus"), 5000L));
        tiers.put("standard", tier(List.of("qwen3-max", "qwen-plus", "qwen3-local"), 120000L));
        chat.setTiers(tiers);

        SystemSettingsVO.AISettings.ModelGroup vo = controller.settings().getData().getAi().getChat();

        assertEquals("standard", vo.getDefaultTier());
        assertEquals("deep", vo.getDeepThinkingTier());
        assertEquals(List.of("fast", "standard"), List.copyOf(vo.getTiers().keySet()), "档位声明顺序保持");
        assertEquals(List.of("qwen3-local", "qwen-plus"), vo.getTiers().get("fast").getCandidates());
        assertEquals(List.of("qwen3-max", "qwen-plus", "qwen3-local"),
                vo.getTiers().get("standard").getCandidates(), "候选顺序必须与配置一致");
        assertEquals(5000L, vo.getTiers().get("fast").getTimeoutMs());
        assertEquals(120000L, vo.getTiers().get("standard").getTimeoutMs());
    }

    @Test
    @DisplayName("未配置档位时返回 null，不抛异常也不返回空对象")
    void returnsNullTiersWhenNotConfigured() {
        aiModelProperties.getChat().setTiers(Map.of());

        SystemSettingsVO.AISettings.ModelGroup vo = controller.settings().getData().getAi().getChat();

        assertNull(vo.getTiers());
    }

    @Test
    @DisplayName("embedding / rerank 组不带档位字段")
    void nonChatGroupsHaveNoTiers() {
        aiModelProperties.getEmbedding().setDefaultModel("qwen3.7-text-embedding");
        aiModelProperties.getRerank().setDefaultModel("qwen3-rerank");

        SystemSettingsVO.AISettings ai = controller.settings().getData().getAi();

        assertEquals("qwen3.7-text-embedding", ai.getEmbedding().getDefaultModel());
        assertNull(ai.getEmbedding().getDefaultTier());
        assertNull(ai.getEmbedding().getTiers());
        assertNull(ai.getRerank().getDefaultTier());
        assertNull(ai.getRerank().getTiers());
    }

    private AIModelProperties.TierConfig tier(List<String> candidates, Long timeoutMs) {
        AIModelProperties.TierConfig config = new AIModelProperties.TierConfig();
        config.setCandidates(candidates);
        config.setTimeoutMs(timeoutMs);
        return config;
    }
}

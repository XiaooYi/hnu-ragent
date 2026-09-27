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

package com.hnu.ragent.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnu.ragent.infra.config.AIModelProperties;
import com.hnu.ragent.rag.config.KeywordProperties;
import com.hnu.ragent.rag.config.MemoryProperties;
import com.hnu.ragent.rag.config.RAGConfigProperties;
import com.hnu.ragent.rag.config.RAGDefaultProperties;
import com.hnu.ragent.rag.config.RAGRateLimitProperties;
import com.hnu.ragent.rag.config.RagTraceProperties;
import com.hnu.ragent.rag.config.SearchChannelProperties;
import com.hnu.ragent.rag.controller.vo.SystemSettingsVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 设置接口暴露档位配置：chat 组带档位，其余模型组不带
 */
class RAGSettingsControllerTest {

    private AIModelProperties aiModelProperties;
    private SearchChannelProperties searchChannelProperties;
    private KeywordProperties keywordProperties;
    private RagTraceProperties ragTraceProperties;
    private RAGConfigProperties ragConfigProperties;
    private RAGSettingsController controller;

    @BeforeEach
    void setUp() {
        aiModelProperties = new AIModelProperties();
        searchChannelProperties = new SearchChannelProperties();
        keywordProperties = new KeywordProperties();
        ragTraceProperties = new RagTraceProperties();
        ragConfigProperties = new RAGConfigProperties();
        controller = new RAGSettingsController(
                searchChannelProperties,
                keywordProperties,
                ragTraceProperties,
                new RAGDefaultProperties(),
                ragConfigProperties,
                new RAGRateLimitProperties(),
                new MemoryProperties(),
                aiModelProperties);
        ReflectionTestUtils.setField(controller, "maxFileSize", DataSize.ofMegabytes(50));
        ReflectionTestUtils.setField(controller, "maxRequestSize", DataSize.ofMegabytes(100));
        ReflectionTestUtils.setField(controller, "vectorType", "pg");
        ReflectionTestUtils.setField(controller, "storageEndpoint", "http://127.0.0.1:9000");
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

    @Test
    @DisplayName("后端选型返回实现类型与访问地址，且不含任何凭据字段")
    void exposesBackendSelection() throws Exception {
        keywordProperties.setType("es");
        keywordProperties.getEs().setIndex("rag_keyword_store");
        keywordProperties.getEs().setUris("http://127.0.0.1:9200");

        SystemSettingsVO.BackendSettings backends = controller.settings().getData().getBackends();

        assertEquals("pg", backends.getVector().getType());
        assertEquals("es", backends.getKeyword().getType());
        assertEquals("rag_keyword_store", backends.getKeyword().getIndex());
        assertEquals("s3-compatible", backends.getStorage().getPlatform());
        assertEquals("http://127.0.0.1:9000", backends.getStorage().getEndpoint());

        String json = new ObjectMapper().writeValueAsString(controller.settings().getData()).toLowerCase();
        assertFalse(json.contains("secret"), "响应不得包含 secret 类字段");
        assertFalse(json.contains("accesskey"), "响应不得包含 accessKey 类字段");
    }

    @Test
    @DisplayName("能力开关取真实配置值")
    void exposesFeatureFlags() {
        ragConfigProperties.setQueryRewriteEnabled(true);
        ragConfigProperties.setRerankEnabled(false);
        ragConfigProperties.setCitationEnabled(true);
        ragConfigProperties.setContextEnrichEnabled(false);
        ragTraceProperties.setEnabled(true);

        SystemSettingsVO.FeatureSettings features = controller.settings().getData().getRag().getFeatures();

        assertEquals(true, features.getQueryRewrite());
        assertEquals(false, features.getRerank());
        assertEquals(true, features.getCitation());
        assertEquals(false, features.getContextEnrich());
        assertEquals(true, features.getTrace());
    }

    @Test
    @DisplayName("检索管线：召回预算未显式配置时跟随融合候选上限，通道阈值如实返回")
    void exposesSearchPipeline() {
        searchChannelProperties.setDefaultTopK(10);
        searchChannelProperties.getFusion().setRerankCandidateLimit(50);
        searchChannelProperties.getScope().setRecallBudget(0);
        searchChannelProperties.getChannels().setTimeoutMs(12_000L);
        searchChannelProperties.getChannels().getIntentDirected().setMinIntentScore(0.45);

        SystemSettingsVO.SearchSettings search = controller.settings().getData().getRag().getSearch();

        assertEquals(10, search.getDefaultTopK());
        assertEquals(50, search.getRecallBudget(), "recall-budget<=0 时回退融合候选上限");
        assertEquals(12_000L, search.getChannels().getTimeoutMs());
        assertEquals(0.45, search.getChannels().getIntentDirected().getMinIntentScore());
        assertEquals("rrf", search.getFusion().getStrategy());
        assertEquals(60, search.getFusion().getRrfK());
        assertEquals(0.2, search.getEvidence().getMinRerankScore());

        searchChannelProperties.getScope().setRecallBudget(80);
        assertEquals(80, controller.settings().getData().getRag().getSearch().getRecallBudget());
    }

    private AIModelProperties.TierConfig tier(List<String> candidates, Long timeoutMs) {
        AIModelProperties.TierConfig config = new AIModelProperties.TierConfig();
        config.setCandidates(candidates);
        config.setTimeoutMs(timeoutMs);
        return config;
    }
}

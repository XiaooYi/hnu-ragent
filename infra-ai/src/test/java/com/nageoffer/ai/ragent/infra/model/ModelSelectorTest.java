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

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * chat 档位选择：档位内选候选、思考请求过滤、preferred 置顶但不排他
 */
class ModelSelectorTest {

    private final ModelHealthStore healthStore = mock(ModelHealthStore.class);
    private AIModelProperties properties;
    private ModelSelector selector;

    @BeforeEach
    void setUp() {
        properties = new AIModelProperties();
        properties.setProviders(new HashMap<>(Map.of("bailian", provider(), "ollama", provider())));

        AIModelProperties.ModelGroup chat = new AIModelProperties.ModelGroup();
        chat.setDefaultTier("standard");
        chat.setDeepThinkingTier("deep");
        chat.setCandidates(List.of(
                candidate("qwen-plus", "bailian", null),
                candidate("qwen3-local", "ollama", null),
                candidate("qwen3-max", "bailian", true)));
        chat.setTiers(new HashMap<>(Map.of(
                "fast", tier(List.of("qwen3-local", "qwen-plus"), 5_000L),
                "standard", tier(List.of("qwen-plus", "qwen3-max"), 120_000L),
                "deep", tier(List.of("qwen3-max", "qwen-plus"), 180_000L))));
        properties.setChat(chat);

        selector = new ModelSelector(properties, healthStore);
    }

    @Test
    @DisplayName("默认档为 standard，显式 tier 覆盖后按该档候选顺序返回")
    void selectsByTier() {
        assertEquals(List.of("qwen-plus", "qwen3-max"), ids(selector.selectChatCandidates(false)));
        assertEquals(List.of("qwen3-local", "qwen-plus"), ids(selector.selectChatCandidates(false, Tier.FAST)));
    }

    @Test
    @DisplayName("深度思考优先走 deep 档，并过滤不支持思考的候选")
    void thinkingOverridesTierAndFiltersUnsupported() {
        // 即便调用点显式传 FAST，thinking=true 仍走 deep-thinking-tier，且 qwen-plus 被过滤
        List<ModelTarget> targets = selector.selectChatCandidates(true, Tier.FAST);

        assertEquals(List.of("qwen3-max"), ids(targets));
    }

    @Test
    @DisplayName("preferred 置队首但不排他，可回退档位内其它候选")
    void preferredIsFirstButNotExclusive() {
        List<ModelTarget> targets = selector.selectChatCandidates(false, Tier.STANDARD, "qwen3-max");

        assertEquals(List.of("qwen3-max", "qwen-plus"), ids(targets));
    }

    @Test
    @DisplayName("preferred 不支持思考时在思考请求下被忽略")
    void preferredIgnoredWhenThinkingUnsupported() {
        List<ModelTarget> targets = selector.selectChatCandidates(true, Tier.DEEP, "qwen-plus");

        assertEquals(List.of("qwen3-max"), ids(targets));
    }

    @Test
    @DisplayName("候选携带所属档位的超时预算；embedding 没有档位预算")
    void timeoutComesFromTier() {
        List<ModelTarget> fast = selector.selectChatCandidates(false, Tier.FAST);
        assertEquals(5_000L, fast.get(0).timeoutMs());

        assertTrue(selector.selectEmbeddingCandidates().stream().allMatch(target -> target.timeoutMs() == null),
                "embedding 无档位预算，超时走 HTTP 客户端默认");
    }

    @Test
    @DisplayName("档位缺失时返回空候选并告警，不抛异常（由启动期校验兜住配置错误）")
    void missingTierYieldsNoCandidates() {
        properties.getChat().setTiers(new HashMap<>());

        assertTrue(selector.selectChatCandidates(false, Tier.FAST).isEmpty());
    }

    private List<String> ids(List<ModelTarget> targets) {
        return targets.stream().map(ModelTarget::id).toList();
    }

    private AIModelProperties.ProviderConfig provider() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://localhost:1");
        provider.setApiKey("k");
        return provider;
    }

    private AIModelProperties.ModelCandidate candidate(String id, String provider, Boolean supportsThinking) {
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId(id);
        candidate.setProvider(provider);
        candidate.setModel(id);
        candidate.setSupportsThinking(supportsThinking);
        return candidate;
    }

    private AIModelProperties.TierConfig tier(List<String> candidates, Long timeoutMs) {
        AIModelProperties.TierConfig tier = new AIModelProperties.TierConfig();
        tier.setCandidates(candidates);
        tier.setTimeoutMs(timeoutMs);
        return tier;
    }
}

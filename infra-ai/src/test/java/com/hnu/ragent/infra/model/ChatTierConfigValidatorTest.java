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

package com.hnu.ragent.infra.model;

import com.hnu.ragent.infra.config.AIModelProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 档位配置启动期校验：结构性错误 fail-fast，避免运行期静默降级
 */
class ChatTierConfigValidatorTest {

    private AIModelProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AIModelProperties();
        AIModelProperties.ModelGroup chat = new AIModelProperties.ModelGroup();
        chat.setDefaultTier("standard");
        chat.setDeepThinkingTier("deep");
        chat.setCandidates(List.of(
                candidate("qwen-plus", null),
                candidate("qwen3-local", null),
                candidate("qwen3-max", true)));
        chat.setTiers(new HashMap<>(Map.of(
                "fast", tier(List.of("qwen3-local"), 5_000L),
                "standard", tier(List.of("qwen-plus"), 120_000L),
                "deep", tier(List.of("qwen3-max"), 180_000L))));
        properties.setChat(chat);
    }

    @Test
    @DisplayName("完整且自洽的档位配置通过校验")
    void passesOnValidConfig() {
        assertDoesNotThrow(() -> new ChatTierConfigValidator(properties).afterPropertiesSet());
    }

    @Test
    @DisplayName("档位引用未登记的候选 id 时启动失败")
    void failsOnUnknownCandidateReference() {
        properties.getChat().getTiers().get("fast").setCandidates(List.of("not-registered"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ChatTierConfigValidator(properties).afterPropertiesSet());

        assertTrue(exception.getMessage().contains("not-registered"), exception.getMessage());
    }

    @Test
    @DisplayName("档位缺少 timeout-ms 或候选为空时启动失败")
    void failsOnMissingTimeoutOrEmptyCandidates() {
        properties.getChat().getTiers().get("standard").setTimeoutMs(null);
        properties.getChat().getTiers().get("fast").setCandidates(List.of());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ChatTierConfigValidator(properties).afterPropertiesSet());

        assertTrue(exception.getMessage().contains("timeout-ms"), exception.getMessage());
        assertTrue(exception.getMessage().contains("候选列表为空"), exception.getMessage());
    }

    @Test
    @DisplayName("deep 档没有可思考候选时启动失败（思考请求会拿到空候选）")
    void failsWhenDeepTierHasNoThinkingCandidate() {
        properties.getChat().getTiers().get("deep").setCandidates(List.of("qwen-plus"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ChatTierConfigValidator(properties).afterPropertiesSet());

        assertTrue(exception.getMessage().contains("无任何已启用且支持思考的候选"), exception.getMessage());
    }

    @Test
    @DisplayName("Tier 枚举未在配置中覆盖时启动失败（调用点会落到档位缺失）")
    void failsWhenEnumTierMissing() {
        properties.getChat().getTiers().remove("fast");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new ChatTierConfigValidator(properties).afterPropertiesSet());

        assertTrue(exception.getMessage().contains("FAST"), exception.getMessage());
    }

    private AIModelProperties.ModelCandidate candidate(String id, Boolean supportsThinking) {
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId(id);
        candidate.setProvider("bailian");
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

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

package com.hnu.ragent.infra.chat;

import com.google.gson.JsonObject;
import com.hnu.ragent.framework.convention.ChatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 思考开关的供应商方言：只有认识 {@code enable_thinking} 的提供商才写该字段
 */
class ChatClientThinkingParamTest {

    private final BaiLianChatClient baiLian = new BaiLianChatClient();
    private final SiliconFlowChatClient siliconFlow = new SiliconFlowChatClient();
    private final OllamaChatClient ollama = new OllamaChatClient();
    private final AIHubMixChatClient aiHubMix = new AIHubMixChatClient();

    @Test
    @DisplayName("百炼/硅基流动显式声明思考开关（开启与关闭都写）")
    void supportedProvidersAlwaysSendExplicitFlag() {
        JsonObject enabled = new JsonObject();
        baiLian.customizeRequestBody(enabled, ChatRequest.builder().thinking(true).build());
        assertTrue(enabled.get("enable_thinking").getAsBoolean());

        JsonObject disabled = new JsonObject();
        siliconFlow.customizeRequestBody(disabled, ChatRequest.builder().thinking(false).build());
        assertFalse(disabled.get("enable_thinking").getAsBoolean());
    }

    @Test
    @DisplayName("未声明支持的提供商不发任何思考字段，避免 unknown_parameter 400")
    void unsupportedProvidersSendNothing() {
        JsonObject ollamaBody = new JsonObject();
        ollama.customizeRequestBody(ollamaBody, ChatRequest.builder().thinking(true).build());
        assertFalse(ollamaBody.has("enable_thinking"));
        assertFalse(ollamaBody.has("thinking"));

        JsonObject aiHubMixBody = new JsonObject();
        aiHubMix.customizeRequestBody(aiHubMixBody, ChatRequest.builder().thinking(true).build());
        assertFalse(aiHubMixBody.has("enable_thinking"));
    }
}

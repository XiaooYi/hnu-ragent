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
import com.hnu.ragent.infra.enums.ModelProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 深度思考开关的供应商方言契约
 * <p>
 * DeepSeek 不认 {@code enable_thinking}，只认 {@code thinking} 对象；反之其它供应商不得带上 {@code thinking}
 */
class DeepSeekChatClientTest {

    private final DeepSeekChatClient deepSeek = new DeepSeekChatClient();
    private final SiliconFlowChatClient siliconFlow = new SiliconFlowChatClient();

    @Test
    @DisplayName("开启思考时发送 thinking.type=enabled，且不发 enable_thinking")
    void sendsEnabledThinkingObject() {
        JsonObject body = new JsonObject();

        deepSeek.customizeRequestBody(body, ChatRequest.builder().thinking(true).build());

        assertEquals("enabled", body.getAsJsonObject("thinking").get("type").getAsString());
        assertFalse(body.has("enable_thinking"));
    }

    @Test
    @DisplayName("关闭思考时显式发送 thinking.type=disabled，避免默认思考拖慢标准档")
    void sendsDisabledThinkingObject() {
        JsonObject body = new JsonObject();

        deepSeek.customizeRequestBody(body, ChatRequest.builder().thinking(false).build());

        assertEquals("disabled", body.getAsJsonObject("thinking").get("type").getAsString());
    }

    @Test
    @DisplayName("其它供应商不携带 thinking 对象，仍走 enable_thinking 方言")
    void otherProvidersKeepDefaultDialect() {
        JsonObject body = new JsonObject();

        siliconFlow.customizeRequestBody(body, ChatRequest.builder().thinking(true).build());

        assertFalse(body.has("thinking"));
        assertTrue(body.has("enable_thinking"));
    }

    @Test
    @DisplayName("provider 标识可被大小写不敏感匹配，路由可达")
    void providerIdMatchesCaseInsensitively() {
        assertEquals("deepseek", deepSeek.provider());
        assertTrue(ModelProvider.DEEP_SEEK.matches("DeepSeek"));
        assertTrue(ModelProvider.DEEP_SEEK.matches("deepseek"));
        assertFalse(ModelProvider.DEEP_SEEK.matches("bailian"));
    }
}

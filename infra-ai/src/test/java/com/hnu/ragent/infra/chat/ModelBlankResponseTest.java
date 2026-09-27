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

import com.hnu.ragent.framework.convention.ChatMessage;
import com.hnu.ragent.framework.convention.ChatRequest;
import com.hnu.ragent.infra.config.AIModelProperties;
import com.hnu.ragent.infra.http.ModelClientErrorType;
import com.hnu.ragent.infra.http.ModelClientException;
import com.hnu.ragent.infra.model.ModelTarget;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 同步空白响应必须触发降级（抛 INVALID_RESPONSE），而不是把空白答案返回给用户
 */
class ModelBlankResponseTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("content 为空串时抛 INVALID_RESPONSE")
    void rejectsEmptyContent() throws IOException {
        assertBlankRejected("");
    }

    @Test
    @DisplayName("content 为纯空白时抛 INVALID_RESPONSE")
    void rejectsWhitespaceContent() throws IOException {
        assertBlankRejected("   \n ");
    }

    private void assertBlankRejected(String content) throws IOException {
        serve("{\"choices\":[{\"message\":{\"content\":\"" + content.replace("\n", "\\n") + "\"}}]}");
        BaiLianChatClient client = new BaiLianChatClient();
        // 单测不启动 Spring 容器，手动注入同步 HTTP 客户端
        ReflectionTestUtils.setField(client, "syncHttpClient", new OkHttpClient());

        ModelClientException exception = assertThrows(ModelClientException.class,
                () -> client.chat(request(), target()));

        assertEquals(ModelClientErrorType.INVALID_RESPONSE, exception.getErrorType(),
                "空白响应必须归类为无效响应，路由层才会切换下一个候选");
    }

    private void serve(String responseJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    private ChatRequest request() {
        return ChatRequest.builder()
                .messages(List.of(ChatMessage.user("你好")))
                .build();
    }

    private ModelTarget target() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        provider.setApiKey("test-key");
        provider.setEndpoints(Map.of("chat", "/chat"));

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("qwen-plus");
        candidate.setProvider("bailian");
        candidate.setModel("qwen-plus-latest");
        return new ModelTarget("qwen-plus", candidate, provider);
    }
}

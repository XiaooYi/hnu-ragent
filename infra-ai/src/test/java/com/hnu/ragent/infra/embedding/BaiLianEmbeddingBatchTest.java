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

package com.hnu.ragent.infra.embedding;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hnu.ragent.infra.config.AIModelProperties;
import com.hnu.ragent.infra.model.ModelTarget;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 百炼向量接口的批量契约
 * <p>
 * 百炼 compatible-mode 单次最多 10 条：超限不是慢而是整批 400，长文档摄取必然踩到，
 * 因此「按 10 切批 + 顺序回填」必须有测试守护
 */
class BaiLianEmbeddingBatchTest {

    private final List<Integer> batchSizes = new ArrayList<>();

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("25 条文本按 10/10/5 切三批，结果等长且不漏批")
    void splitsIntoBatchesOfTen() throws IOException {
        serveEchoingEmbeddings();
        BaiLianEmbeddingClient client = new BaiLianEmbeddingClient(new OkHttpClient());

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            texts.add("文本-" + i);
        }

        List<List<Float>> vectors = client.embedBatch(texts, target());

        assertEquals(List.of(10, 10, 5), batchSizes, "每批不得超过 10 条");
        assertEquals(25, vectors.size(), "返回向量数量必须等于入参数量");
        // 每个向量首元素等于源文本下标，验证分批后顺序未错位
        for (int i = 0; i < vectors.size(); i++) {
            assertEquals((float) i, vectors.get(i).get(0), 1e-6F, "第 " + i + " 条向量错位");
        }
    }

    @Test
    @DisplayName("恰好 10 条时只发一次请求")
    void doesNotSplitWhenWithinLimit() throws IOException {
        serveEchoingEmbeddings();
        BaiLianEmbeddingClient client = new BaiLianEmbeddingClient(new OkHttpClient());

        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            texts.add("文本-" + i);
        }

        client.embedBatch(texts, target());

        assertEquals(List.of(10), batchSizes);
    }

    /**
     * 端点按请求条数返回等量向量，且向量首元素为该文本下标，便于断言顺序
     */
    private void serveEchoingEmbeddings() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-mode/v1/embeddings", exchange -> {
            String requestBody;
            try (InputStream in = exchange.getRequestBody()) {
                requestBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonArray inputs = JsonParser.parseString(requestBody).getAsJsonObject().getAsJsonArray("input");
            batchSizes.add(inputs.size());

            JsonArray data = new JsonArray();
            for (int i = 0; i < inputs.size(); i++) {
                String text = inputs.get(i).getAsString();
                int sourceIndex = Integer.parseInt(text.substring("文本-".length()));
                JsonArray embedding = new JsonArray();
                embedding.add((float) sourceIndex);
                embedding.add(0.5f);
                JsonObject item = new JsonObject();
                item.add("embedding", embedding);
                data.add(item);
            }
            JsonObject response = new JsonObject();
            response.add("data", data);

            byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    private ModelTarget target() {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        provider.setApiKey("test-key");
        provider.setEndpoints(Map.of("embedding", "/compatible-mode/v1/embeddings"));

        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId("qwen3.7-text-embedding");
        candidate.setProvider("bailian");
        candidate.setModel("qwen3.7-text-embedding");
        candidate.setDimension(1024);
        return new ModelTarget("qwen3.7-text-embedding", candidate, provider);
    }
}

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

package com.hnu.ragent.rag.core.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnu.ragent.framework.convention.RetrievedChunk;
import com.hnu.ragent.rag.config.GraphProperties;
import com.hnu.ragent.rag.config.SearchChannelProperties;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LightRAG 客户端契约与降级（离线，使用 JDK 内置 HttpServer 作为 stub）
 */
@DisplayName("LightRAG 客户端")
class LightRagClientTest {

    private static final String QUERY_BODY = """
            {
              "response": "图谱回答",
              "references": [
                {
                  "reference_id": "ref-1",
                  "file_path": "kb_hr_1001.txt",
                  "content": ["教务处负责转专业审核"]
                },
                {
                  "reference_id": "ref-2",
                  "file_path": "kb_other_2002",
                  "content": ["别库的残留证据"]
                }
              ]
            }
            """;

    private HttpServer server;
    private String baseUrl;

    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final List<String> deletedPaths = new ArrayList<>();

    private volatile int queryResponseCode = 200;
    private volatile String queryResponseBody = QUERY_BODY;

    private GraphProperties graphProperties;
    private LightRagClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath.set(exchange.getRequestURI().getPath());
            lastMethod.set(exchange.getRequestMethod());
            lastApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if ("/documents".equals(exchange.getRequestURI().getPath()) && "GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, """
                        {"statuses":{"processed":[
                          {"id":"lg-doc-1","file_path":"kb_hr_1001.txt"},
                          {"id":"lg-doc-2","file_path":"kb_other_2002"}
                        ]}}
                        """);
                return;
            }
            if ("/documents/delete_document".equals(exchange.getRequestURI().getPath())) {
                deletedPaths.add(lastBody.get());
            }
            if ("/query".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, queryResponseCode, queryResponseBody);
                return;
            }
            if ("/graph/label/popular".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, "[\"教务处\",{\"label\":\"学籍科\"}]");
                return;
            }
            respond(exchange, 200, "{}");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        graphProperties = new GraphProperties();
        graphProperties.setType("lightrag");
        graphProperties.getLightrag().setBaseUrl(baseUrl);
        SearchChannelProperties searchProperties = new SearchChannelProperties();
        client = new LightRagClient(new OkHttpClient(), new ObjectMapper(), graphProperties, searchProperties);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("检索请求带 only_need_context / mode / top_k，并按库切分证据")
    void retrieveByScopeSplitsByCollection() {
        graphProperties.getLightrag().setApiKey("lg-key");

        GraphEvidence evidence = client.retrieveByScope("转专业谁审核", "hybrid", 5, List.of("kb_hr"));

        assertEquals("/query", lastPath.get());
        assertEquals("POST", lastMethod.get());
        assertEquals("lg-key", lastApiKey.get());
        assertTrue(lastBody.get().contains("\"only_need_context\":true"), lastBody.get());
        assertTrue(lastBody.get().contains("\"mode\":\"hybrid\""), lastBody.get());
        assertTrue(lastBody.get().contains("\"top_k\":5"), lastBody.get());

        assertEquals(1, evidence.matched().size());
        RetrievedChunk matched = evidence.matched().get(0);
        assertEquals("ref-1", matched.getId());
        assertEquals("教务处负责转专业审核", matched.getText());
        assertEquals("kb_hr", matched.getCollectionName());
        assertEquals("1001", matched.getDocId());
        assertNull(matched.getDocName(), "解析出 docId 时留空 docName，交富化补真实标题");
        assertEquals(1.0f, matched.getScore(), 0.0001f);

        assertEquals(1, evidence.unmatched().size());
        assertEquals("别库的残留证据", evidence.unmatched().get(0).getText());
        assertEquals(0.5f, evidence.unmatched().get(0).getScore(), 0.0001f, "两份共用全图名次");
    }

    @Test
    @DisplayName("未配置 api-key 时不发送鉴权头，且未配置库时全部归入命中份")
    void withoutApiKeyAndWithoutFilter() {
        GraphEvidence evidence = client.retrieveByScope("问题", null, 0, List.of());

        assertNull(lastApiKey.get());
        assertEquals(2, evidence.matched().size());
        assertTrue(evidence.unmatched().isEmpty());
        assertTrue(lastBody.get().contains("\"mode\":\"hybrid\""), "模式缺省回退 hybrid");
    }

    @Test
    @DisplayName("references 缺失时按库过滤丢弃兜底上下文，不过滤时兜底为单块")
    void fallbackContextBehavior() {
        queryResponseBody = "{\"response\":\"兜底上下文\"}";

        assertTrue(client.retrieveByScope("问题", "hybrid", 5, List.of("kb_hr")).matched().isEmpty());

        GraphEvidence evidence = client.retrieveByScope("问题", "hybrid", 5, List.of());
        assertEquals(1, evidence.matched().size());
        assertEquals("graph:context", evidence.matched().get(0).getId());
    }

    @Test
    @DisplayName("非 2xx 与非法 JSON 都降级为空证据，不抛异常")
    void degradesOnFailure() {
        queryResponseCode = 500;
        assertTrue(client.retrieveByScope("问题", "hybrid", 5, List.of("kb_hr")).matched().isEmpty());

        queryResponseCode = 200;
        queryResponseBody = "not-json";
        assertTrue(client.retrieveByScope("问题", "hybrid", 5, List.of("kb_hr")).matched().isEmpty());
    }

    @Test
    @DisplayName("写入按 file_source 编码，删除按 file_path 反查内部 doc_id")
    void insertAndDelete() {
        client.insertText("文档全文", GraphFileSource.encode("kb_hr", "1001"));
        assertEquals("/documents/text", lastPath.get());
        assertTrue(lastBody.get().contains("\"file_source\":\"kb_hr_1001\""), lastBody.get());

        client.deleteByDoc("1001");
        assertEquals("/documents/delete_document", lastPath.get());
        assertEquals(1, deletedPaths.size());
        assertTrue(deletedPaths.get(0).contains("lg-doc-1"), deletedPaths.get(0));

        client.deleteByCollection("kb_other");
        assertEquals(2, deletedPaths.size());
        assertTrue(deletedPaths.get(1).contains("lg-doc-2"), deletedPaths.get(1));
        assertTrue(!deletedPaths.get(1).contains("lg-doc-1"), "等值匹配不应连带删除 kb_hr 的数据");
    }

    @Test
    @DisplayName("可视化取图与标签检索")
    void fetchGraphAndLabels() {
        JsonNode graph = client.fetchGraph("*", 2, 200);
        assertNotNull(graph);
        assertTrue(lastPath.get().startsWith("/graphs"));

        List<String> labels = client.fetchLabels(null, 20);
        assertEquals(List.of("教务处", "学籍科"), labels);
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}

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

package com.hnu.ragent.rag.core.retrieve.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnu.ragent.framework.convention.RetrievedChunk;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * You.com 联网检索通道单元测试（离线，使用 JDK 内置 HttpServer 作为 stub，不依赖真实 Key）
 */
@DisplayName("You.com 联网检索通道")
class YouComWebSearchChannelTest {

    private static final String SAMPLE_BODY = """
            {
              "results": {
                "web": [
                  {
                    "url": "https://example.com/a",
                    "title": "网页结果A",
                    "description": "描述A",
                    "snippets": ["片段A1", "片段A2"]
                  },
                  {
                    "url": "https://example.com/b",
                    "title": "网页结果B",
                    "description": "描述B"
                  }
                ],
                "news": [
                  {
                    "url": "https://example.com/news",
                    "title": "新闻结果",
                    "description": "新闻描述"
                  }
                ]
              },
              "metadata": {"query": "test"}
            }
            """;

    private HttpServer server;
    private String stubUrl;

    private final AtomicReference<String> lastApiKey = new AtomicReference<>();
    private final AtomicReference<Map<String, String>> lastQueryParams = new AtomicReference<>();
    private volatile int responseCode = 200;
    private volatile String responseBody = SAMPLE_BODY;

    private SearchChannelProperties properties;
    private YouComWebSearchChannel channel;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/search", exchange -> {
            lastApiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            lastQueryParams.set(parseQuery(exchange.getRequestURI().getRawQuery()));
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseCode, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
        stubUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/search";

        properties = new SearchChannelProperties();
        properties.getChannels().getWebSearch().setApiUrl(stubUrl);
        channel = new YouComWebSearchChannel(new OkHttpClient(), new ObjectMapper(), properties) {
            @Override
            protected String readEnv(String name) {
                return null;
            }
        };
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("未启用或无 Key 时通道不生效")
    void disabledWithoutFlagOrKey() {
        assertFalse(channel.isEnabled(context("转专业")));

        properties.getChannels().getWebSearch().setEnabled(true);
        assertFalse(channel.isEnabled(context("转专业")), "没有 api-key 且环境变量为空时不启用");

        properties.getChannels().getWebSearch().setApiKey("stub-key");
        assertTrue(channel.isEnabled(context("转专业")));
    }

    @Test
    @DisplayName("请求带 query / count 与 X-API-Key，结果合并 web+news 并按 count 截断")
    void mergesSectionsAndTruncatesToCount() {
        properties.getChannels().getWebSearch().setEnabled(true);
        properties.getChannels().getWebSearch().setApiKey("stub-key");
        properties.getChannels().getWebSearch().setCount(2);

        SearchChannelResult result = channel.search(context("湖大 转专业"));

        assertEquals(SearchChannelType.WEB_SEARCH, result.getChannelType());
        assertEquals("YouComWebSearch", result.getChannelName());
        assertEquals(2, result.getChunks().size(), "web(2) + news(1) 合并后截断到 count=2");
        assertEquals(List.of("https://example.com/a", "https://example.com/b"),
                result.getChunks().stream().map(RetrievedChunk::getId).toList());

        String text = result.getChunks().get(0).getText();
        assertTrue(text.contains("【网页结果A】"), text);
        assertTrue(text.contains("描述A"), text);
        assertTrue(text.contains("片段A2"), text);
        assertTrue(text.contains("来源: https://example.com/a"), text);

        Map<String, String> params = lastQueryParams.get();
        assertNotNull(params);
        assertEquals("湖大 转专业", params.get("query"));
        assertEquals("2", params.get("count"));
        assertEquals("stub-key", lastApiKey.get());

        assertEquals(1.0f, result.getChunks().get(0).getScore(), 0.0001f);
        assertNull(result.getChunks().get(0).getDocId(), "联网结果无本地文档归属");
    }

    @Test
    @DisplayName("count 非法回退默认值，超过上限按 20 处理")
    void normalizesCount() {
        SearchChannelProperties.WebSearch config = properties.getChannels().getWebSearch();
        config.setCount(0);
        assertEquals(5, channel.resolveCount(config));

        config.setCount(-3);
        assertEquals(5, channel.resolveCount(config));

        config.setCount(100);
        assertEquals(20, channel.resolveCount(config));
    }

    @Test
    @DisplayName("非 2xx 与非法 JSON 都降级为空结果，不抛异常")
    void degradesOnHttpOrParseFailure() {
        properties.getChannels().getWebSearch().setEnabled(true);
        properties.getChannels().getWebSearch().setApiKey("stub-key");

        responseCode = 429;
        SearchChannelResult limited = channel.search(context("转专业"));
        assertTrue(limited.getChunks().isEmpty());
        assertEquals(SearchChannelType.WEB_SEARCH, limited.getChannelType());

        responseCode = 200;
        responseBody = "not-json";
        assertTrue(channel.search(context("转专业")).getChunks().isEmpty());
    }

    @Test
    @DisplayName("api-url 非法与问题为空时返回空结果")
    void degradesOnBadUrlOrBlankQuestion() {
        properties.getChannels().getWebSearch().setEnabled(true);
        properties.getChannels().getWebSearch().setApiKey("stub-key");

        properties.getChannels().getWebSearch().setApiUrl("not a url");
        assertTrue(channel.search(context("转专业")).getChunks().isEmpty());

        properties.getChannels().getWebSearch().setApiUrl(stubUrl);
        assertTrue(channel.search(context("  ")).getChunks().isEmpty());
    }

    @Test
    @DisplayName("标题/描述/摘录/链接都为空的条目被丢弃；只有链接的条目保留可溯源的来源")
    void dropsEmptyResults() throws Exception {
        String body = """
                {"results":{"web":[
                  {"url":"https://example.com/link-only"},
                  {"title":"无链接无摘录"},
                  {"url":"","title":"","description":"","snippets":[]}
                ]}}
                """;
        List<RetrievedChunk> chunks = channel.parseChunks(body, 5);
        assertEquals(2, chunks.size(), "只有链接的条目仍可溯源，全空条目被丢弃");
        assertEquals("https://example.com/link-only", chunks.get(0).getId());
        assertEquals("来源: https://example.com/link-only", chunks.get(0).getText());
        assertNull(chunks.get(1).getId());
        assertEquals("【无链接无摘录】", chunks.get(1).getText());
    }

    private SearchContext context(String question) {
        return SearchContext.builder()
                .originalQuestion(question)
                .rewrittenQuestion(question)
                .build();
    }

    private Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int index = pair.indexOf('=');
            if (index < 0) {
                continue;
            }
            String name = URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8);
            params.put(name, value);
        }
        return params;
    }
}

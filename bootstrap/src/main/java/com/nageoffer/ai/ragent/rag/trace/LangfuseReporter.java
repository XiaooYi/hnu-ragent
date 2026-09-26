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

package com.nageoffer.ai.ragent.rag.trace;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentRunResult;
import com.nageoffer.ai.ragent.rag.config.LangfuseProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * LangFuse 链路上报
 * <p>
 * 只在配置齐全时注册；上报是 **best-effort**：任何失败（网络、鉴权、限流、序列化）都只记 WARN，
 * 绝不改变业务结果——观测系统不该成为线上故障源
 * <p>
 * 同步上报而非异步队列：一次运行一条批量请求，量级很小；引入线程池与队列会让「上报失败」的排查变复杂
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rag.trace.langfuse", name = "enabled", havingValue = "true")
public class LangfuseReporter {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final LangfuseProperties properties;
    private final LangfusePayloadBuilder payloadBuilder;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public LangfuseReporter(LangfuseProperties properties,
                            LangfusePayloadBuilder payloadBuilder,
                            ObjectMapper objectMapper,
                            @Qualifier("syncHttpClient") OkHttpClient httpClient) {
        this.properties = properties;
        this.payloadBuilder = payloadBuilder;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        if (!properties.usable()) {
            log.warn("LangFuse 已开启但配置不完整（host / public-key / secret-key），上报将被跳过");
        }
    }

    /**
     * 上报一次 RAG 运行
     */
    public void reportRagRun(RagTraceRunDO run, List<RagTraceNodeDO> nodes) {
        post(payloadBuilder.forRagRun(run, nodes), "rag-trace=" + (run == null ? "-" : run.getTraceId()));
    }

    /**
     * 上报一次 Agent 运行（含工具步骤）
     */
    public void reportAgentRun(String conversationId, String question, AgentRunResult result) {
        if (!Boolean.TRUE.equals(properties.getAgentEnabled())) {
            return;
        }
        post(payloadBuilder.forAgentRun(conversationId, question, result),
                "agent-conversation=" + conversationId);
    }

    private void post(Map<String, Object> payload, String logKey) {
        if (!properties.usable() || payload.isEmpty()) {
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(payload);
            OkHttpClient client = httpClient.newBuilder()
                    .callTimeout(Duration.ofMillis(Math.max(500, properties.getTimeoutMs())))
                    .readTimeout(Duration.ofMillis(Math.max(500, properties.getTimeoutMs())))
                    .build();
            Request request = new Request.Builder()
                    .url(StrUtil.removeSuffix(properties.getHost(), "/") + "/api/public/ingestion")
                    .header("Authorization", basicAuth())
                    .post(RequestBody.create(body, JSON))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("LangFuse 上报失败（不影响业务）, key={}, code={}", logKey, response.code());
                }
            }
        } catch (Exception e) {
            log.warn("LangFuse 上报异常（不影响业务）, key={}", logKey, e);
        }
    }

    private String basicAuth() {
        String raw = properties.getPublicKey() + ":" + properties.getSecretKey();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}

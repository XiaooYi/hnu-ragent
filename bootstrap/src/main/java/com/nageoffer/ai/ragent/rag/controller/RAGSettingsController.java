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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.config.KeywordProperties;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.AISettings;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.BackendSettings;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.DefaultSettings;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.FeatureSettings;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.MemorySettings;
import com.nageoffer.ai.ragent.rag.controller.vo.SystemSettingsVO.SearchSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 设置控制器，负责后端选型、检索管线与 AI 模型等配置信息的只读查询
 */
@RestController
@RequiredArgsConstructor
public class RAGSettingsController {

    private final SearchChannelProperties searchChannelProperties;
    private final KeywordProperties keywordProperties;
    private final RagTraceProperties ragTraceProperties;
    private final RAGDefaultProperties ragDefaultProperties;
    private final RAGConfigProperties ragConfigProperties;
    private final RAGRateLimitProperties ragRateLimitProperties;
    private final MemoryProperties memoryProperties;
    private final AIModelProperties aiModelProperties;

    @Value("${spring.servlet.multipart.max-file-size:50MB}")
    private DataSize maxFileSize;

    @Value("${spring.servlet.multipart.max-request-size:100MB}")
    private DataSize maxRequestSize;

    @Value("${rag.vector.type:milvus}")
    private String vectorType;

    @Value("${rustfs.url:}")
    private String storageEndpoint;

    /**
     * 获取系统后端选型、检索管线、RAG、AI 模型等配置信息
     */
    @GetMapping("/rag/settings")
    public Result<SystemSettingsVO> settings() {
        SystemSettingsVO response = SystemSettingsVO.builder()
                .backends(toBackendSettings())
                .upload(SystemSettingsVO.UploadSettings.builder()
                        .maxFileSize(maxFileSize.toBytes())
                        .maxRequestSize(maxRequestSize.toBytes())
                        .build())
                .rag(SystemSettingsVO.RagSettings.builder()
                        .defaultConfig(toDefaultSettings(ragDefaultProperties))
                        .features(toFeatureSettings())
                        .search(toSearchSettings(searchChannelProperties))
                        .queryRewrite(SystemSettingsVO.QueryRewriteSettings.builder()
                                .enabled(ragConfigProperties.getQueryRewriteEnabled())
                                .build())
                        .rateLimit(SystemSettingsVO.RateLimitSettings.builder()
                                .global(SystemSettingsVO.GlobalRateLimit.builder()
                                        .enabled(ragRateLimitProperties.getGlobalEnabled())
                                        .maxConcurrent(ragRateLimitProperties.getGlobalMaxConcurrent())
                                        .maxWaitSeconds(ragRateLimitProperties.getGlobalMaxWaitSeconds())
                                        .leaseSeconds(ragRateLimitProperties.getGlobalLeaseSeconds())
                                        .pollIntervalMs(ragRateLimitProperties.getGlobalPollIntervalMs())
                                        .build())
                                .build())
                        .memory(toMemorySettings(memoryProperties))
                        .build())
                .ai(toAISettings(aiModelProperties))
                .build();
        return Results.success(response);
    }

    /**
     * 后端选型：只暴露实现类型与访问地址，不暴露任何凭据
     */
    private BackendSettings toBackendSettings() {
        return BackendSettings.builder()
                .vector(BackendSettings.VectorBackend.builder()
                        .type(vectorType)
                        .build())
                .keyword(BackendSettings.KeywordBackend.builder()
                        .type(keywordProperties.getType())
                        .uris(keywordProperties.getEs().getUris())
                        .index(keywordProperties.getEs().getIndex())
                        .analyzer(keywordProperties.getEs().getAnalyzer())
                        .searchAnalyzer(keywordProperties.getEs().getSearchAnalyzer())
                        .build())
                .storage(BackendSettings.StorageBackend.builder()
                        .platform("s3-compatible")
                        .endpoint(storageEndpoint)
                        .build())
                .build();
    }

    private FeatureSettings toFeatureSettings() {
        return FeatureSettings.builder()
                .queryRewrite(ragConfigProperties.getQueryRewriteEnabled())
                .rerank(ragConfigProperties.getRerankEnabled())
                .citation(ragConfigProperties.getCitationEnabled())
                .contextEnrich(ragConfigProperties.getContextEnrichEnabled())
                .trace(ragTraceProperties.isEnabled())
                .build();
    }

    /**
     * 检索管线：召回预算按与执行侧一致的规则折算（<=0 时跟随融合候选上限）
     */
    private SearchSettings toSearchSettings(SearchChannelProperties props) {
        SearchChannelProperties.Fusion fusion = props.getFusion();
        SearchChannelProperties.Channels channels = props.getChannels();
        return SearchSettings.builder()
                .defaultTopK(props.getDefaultTopK())
                .recallBudget(props.getScope().resolveRecallBudget(fusion.getRerankCandidateLimit()))
                .channels(SearchSettings.Channels.builder()
                        .timeoutMs(channels.getTimeoutMs())
                        .vectorGlobal(SearchSettings.VectorGlobal.builder()
                                .enabled(channels.getVectorGlobal().isEnabled())
                                .confidenceThreshold(channels.getVectorGlobal().getConfidenceThreshold())
                                .singleIntentSupplementThreshold(
                                        channels.getVectorGlobal().getSingleIntentSupplementThreshold())
                                .build())
                        .intentDirected(SearchSettings.IntentDirected.builder()
                                .enabled(channels.getIntentDirected().isEnabled())
                                .minIntentScore(channels.getIntentDirected().getMinIntentScore())
                                .build())
                        .keyword(SearchSettings.Keyword.builder()
                                .enabled(channels.getKeyword().isEnabled())
                                .mode(channels.getKeyword().getMode())
                                .build())
                        .build())
                .fusion(SearchSettings.Fusion.builder()
                        .strategy(fusion.getStrategy())
                        .rrfK(fusion.getRrfK())
                        .rerankCandidateLimit(fusion.getRerankCandidateLimit())
                        .build())
                .evidence(SearchSettings.Evidence.builder()
                        .minRerankScore(props.getEvidence().getMinRerankScore())
                        .build())
                .build();
    }

    private DefaultSettings toDefaultSettings(RAGDefaultProperties props) {
        return DefaultSettings.builder()
                .collectionName(props.getCollectionName())
                .dimension(props.getDimension())
                .metricType(props.getMetricType())
                .sseTimeoutMs(props.getSseTimeoutMs())
                .build();
    }

    private MemorySettings toMemorySettings(MemoryProperties props) {
        return MemorySettings.builder()
                .historyKeepTurns(props.getHistoryKeepTurns())
                .summaryEnabled(props.getSummaryEnabled())
                .summaryStartTurns(props.getSummaryStartTurns())
                .summaryMaxChars(props.getSummaryMaxChars())
                .titleMaxLength(props.getTitleMaxLength())
                .build();
    }

    private AISettings toAISettings(AIModelProperties props) {
        Map<String, AISettings.ProviderConfig> providers = new HashMap<>();
        if (props.getProviders() != null) {
            props.getProviders().forEach((k, v) -> providers.put(k, AISettings.ProviderConfig.builder()
                    .url(v.getUrl())
                    .apiKey(maskApiKey(v.getApiKey()))
                    .endpoints(v.getEndpoints())
                    .build()));
        }

        return AISettings.builder()
                .providers(providers)
                .chat(toModelGroup(props.getChat(), true))
                .embedding(toModelGroup(props.getEmbedding(), false))
                .rerank(toModelGroup(props.getRerank(), false))
                .selection(props.getSelection() == null
                        ? null
                        : AISettings.Selection.builder()
                          .failureThreshold(props.getSelection().getFailureThreshold())
                          .openDurationMs(props.getSelection().getOpenDurationMs())
                          .build())
                .stream(props.getStream() == null
                        ? null
                        : AISettings.Stream.builder()
                          .messageChunkSize(props.getStream().getMessageChunkSize())
                          .build())
                .build();
    }

    /**
     * 只有 chat 组走档位路由，其余模型组仍按 defaultModel + priority 排序，因此不暴露档位字段
     */
    private AISettings.ModelGroup toModelGroup(AIModelProperties.ModelGroup group, boolean chatGroup) {
        if (group == null) {
            return null;
        }
        return AISettings.ModelGroup.builder()
                .defaultModel(group.getDefaultModel())
                .deepThinkingModel(group.getDeepThinkingModel())
                .candidates(group.getCandidates() == null
                        ? null
                        : group.getCandidates().stream()
                          .map(c -> AISettings.ModelCandidate.builder()
                                    .id(c.getId())
                                    .provider(c.getProvider())
                                    .model(c.getModel())
                                    .url(c.getUrl())
                                    .dimension(c.getDimension())
                                    .priority(c.getPriority())
                                    .enabled(c.getEnabled())
                                    .supportsThinking(c.getSupportsThinking())
                                    .build())
                          .collect(Collectors.toList()))
                .defaultTier(chatGroup ? group.getDefaultTier() : null)
                .deepThinkingTier(chatGroup ? group.getDeepThinkingTier() : null)
                .tiers(chatGroup ? toTiers(group.getTiers()) : null)
                .build();
    }

    /**
     * 档位映射，保持 yaml 中的声明顺序（前端按该顺序之外的既定顺序展示）
     */
    private Map<String, AISettings.TierConfig> toTiers(Map<String, AIModelProperties.TierConfig> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return null;
        }
        return tiers.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> AISettings.TierConfig.builder()
                                .candidates(e.getValue() == null ? null : e.getValue().getCandidates())
                                .timeoutMs(e.getValue() == null ? null : e.getValue().getTimeoutMs())
                                .build(),
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private String maskApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            return null;
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 10) {
            return "******";
        }
        return trimmed.substring(0, 6) + "***" + trimmed.substring(trimmed.length() - 4);
    }
}

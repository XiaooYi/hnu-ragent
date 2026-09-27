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

package com.hnu.ragent.rag.controller.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * 系统设置视图对象
 * 包含RAG和AI相关的配置信息
 */
@Setter
@Getter
public class SystemSettingsVO {

    private RagSettings rag;
    private AISettings ai;
    private UploadSettings upload;
    private BackendSettings backends;

    public SystemSettingsVO(RagSettings rag, AISettings ai, UploadSettings upload, BackendSettings backends) {
        this.rag = rag;
        this.ai = ai;
        this.upload = upload;
        this.backends = backends;
    }

    public static SystemSettingsVOBuilder builder() {
        return new SystemSettingsVOBuilder();
    }

    public static class SystemSettingsVOBuilder {
        private RagSettings rag;
        private AISettings ai;
        private UploadSettings upload;
        private BackendSettings backends;

        public SystemSettingsVOBuilder rag(RagSettings rag) {
            this.rag = rag;
            return this;
        }

        public SystemSettingsVOBuilder ai(AISettings ai) {
            this.ai = ai;
            return this;
        }

        public SystemSettingsVOBuilder upload(UploadSettings upload) {
            this.upload = upload;
            return this;
        }

        public SystemSettingsVOBuilder backends(BackendSettings backends) {
            this.backends = backends;
            return this;
        }

        public SystemSettingsVO build() {
            return new SystemSettingsVO(rag, ai, upload, backends);
        }
    }

    /**
     * 后端选型视图：向量库 / 关键词检索 / 文件存储当前用的实现（只暴露地址，不含凭据）
     */
    @Data
    @Builder
    public static class BackendSettings {
        private VectorBackend vector;
        private KeywordBackend keyword;
        private StorageBackend storage;

        @Data
        @Builder
        public static class VectorBackend {
            /**
             * 向量后端类型：pg / milvus
             */
            private String type;
        }

        @Data
        @Builder
        public static class KeywordBackend {
            /**
             * 关键词后端类型：none / es
             */
            private String type;
            private String uris;
            private String index;
            private String analyzer;
            private String searchAnalyzer;
        }

        @Data
        @Builder
        public static class StorageBackend {
            /**
             * 存储平台（本仓库为 S3 兼容实现）
             */
            private String platform;
            /**
             * 访问地址（不含 access key / secret）
             */
            private String endpoint;
        }
    }

    @Data
    @Builder
    public static class UploadSettings {
        private Long maxFileSize;
        private Long maxRequestSize;
    }

    @Data
    @Builder
    public static class AISettings {
        private Map<String, ProviderConfig> providers;
        private ModelGroup chat;
        private ModelGroup embedding;
        private ModelGroup rerank;
        private Selection selection;
        private Stream stream;

        @Data
        @Builder
        public static class ProviderConfig {
            private String url;
            private String apiKey;
            private Map<String, String> endpoints;
        }

        @Data
        @Builder
        public static class ModelGroup {
            // defaultModel / deepThinkingModel 供 embedding / rerank / vlm 使用；chat 组路由已改走档位（tiers）
            private String defaultModel;
            private String deepThinkingModel;
            private List<ModelCandidate> candidates;
            // 以下为 chat 组的档位机制字段，embedding / rerank / vlm 为 null
            private String defaultTier;
            private String deepThinkingTier;
            private Map<String, TierConfig> tiers;
        }

        /**
         * 档位配置：一组有序候选 + 一个调用预算（流式为首包预算，同步为整段上限）
         */
        @Data
        @Builder
        public static class TierConfig {
            private List<String> candidates;
            private Long timeoutMs;
        }

        @Data
        @Builder
        public static class ModelCandidate {
            private String id;
            private String provider;
            private String model;
            private String url;
            private Integer dimension;
            private Integer priority;
            private Boolean enabled;
            private Boolean supportsThinking;
        }

        @Data
        @Builder
        public static class Selection {
            private Integer failureThreshold;
            private Long openDurationMs;
        }

        @Data
        @Builder
        public static class Stream {
            private Integer messageChunkSize;
        }
    }

    @Data
    @Builder
    public static class DefaultSettings {
        private String collectionName;
        private Integer dimension;
        private String metricType;
        /**
         * SSE 全局超时（毫秒），兜底防止连接泄漏
         */
        private Long sseTimeoutMs;
    }

    @Data
    @Builder
    public static class MemorySettings {
        private Integer historyKeepTurns;
        private Boolean summaryEnabled;
        private Integer summaryStartTurns;
        private Integer summaryMaxChars;
        private Integer titleMaxLength;
    }

    @Setter
    @Getter
    public static class RagSettings {
        @JsonProperty("default")
        private DefaultSettings defaultConfig;
        private FeatureSettings features;
        private SearchSettings search;
        private QueryRewriteSettings queryRewrite;
        private RateLimitSettings rateLimit;
        private MemorySettings memory;

        public RagSettings(DefaultSettings defaultConfig, FeatureSettings features, SearchSettings search,
                           QueryRewriteSettings queryRewrite, RateLimitSettings rateLimit, MemorySettings memory) {
            this.defaultConfig = defaultConfig;
            this.features = features;
            this.search = search;
            this.queryRewrite = queryRewrite;
            this.rateLimit = rateLimit;
            this.memory = memory;
        }

        public static RagSettingsBuilder builder() {
            return new RagSettingsBuilder();
        }

        public static class RagSettingsBuilder {
            private DefaultSettings defaultConfig;
            private FeatureSettings features;
            private SearchSettings search;
            private QueryRewriteSettings queryRewrite;
            private RateLimitSettings rateLimit;
            private MemorySettings memory;

            public RagSettingsBuilder defaultConfig(DefaultSettings defaultConfig) {
                this.defaultConfig = defaultConfig;
                return this;
            }

            public RagSettingsBuilder features(FeatureSettings features) {
                this.features = features;
                return this;
            }

            public RagSettingsBuilder search(SearchSettings search) {
                this.search = search;
                return this;
            }

            public RagSettingsBuilder queryRewrite(QueryRewriteSettings queryRewrite) {
                this.queryRewrite = queryRewrite;
                return this;
            }

            public RagSettingsBuilder rateLimit(RateLimitSettings rateLimit) {
                this.rateLimit = rateLimit;
                return this;
            }

            public RagSettingsBuilder memory(MemorySettings memory) {
                this.memory = memory;
                return this;
            }

            public RagSettings build() {
                return new RagSettings(defaultConfig, features, search, queryRewrite, rateLimit, memory);
            }
        }
    }

    /**
     * 能力开关视图：与 RAGConfigProperties / RagTraceProperties 一一对应
     */
    @Data
    @Builder
    public static class FeatureSettings {
        private Boolean queryRewrite;
        private Boolean rerank;
        private Boolean citation;
        private Boolean contextEnrich;
        private Boolean trace;
    }

    /**
     * 检索管线视图：展示线上真正生效的通道、融合与闸门参数
     */
    @Data
    @Builder
    public static class SearchSettings {
        /**
         * 最终进入上下文的条数
         */
        private Integer defaultTopK;
        /**
         * 通道取数深度（已按 recall-budget 的解析规则折算）
         */
        private Integer recallBudget;
        private Channels channels;
        private Fusion fusion;
        private Evidence evidence;

        @Data
        @Builder
        public static class Channels {
            private Long timeoutMs;
            private VectorGlobal vectorGlobal;
            private IntentDirected intentDirected;
            private Keyword keyword;
        }

        @Data
        @Builder
        public static class VectorGlobal {
            private Boolean enabled;
            private Double confidenceThreshold;
            private Double singleIntentSupplementThreshold;
        }

        @Data
        @Builder
        public static class IntentDirected {
            private Boolean enabled;
            private Double minIntentScore;
        }

        @Data
        @Builder
        public static class Keyword {
            private Boolean enabled;
            private String mode;
        }

        @Data
        @Builder
        public static class Fusion {
            private String strategy;
            private Integer rrfK;
            private Integer rerankCandidateLimit;
        }

        @Data
        @Builder
        public static class Evidence {
            private Double minRerankScore;
        }
    }

    @Setter
    @Getter
    public static class QueryRewriteSettings {
        private Boolean enabled;

        public QueryRewriteSettings(Boolean enabled) {
            this.enabled = enabled;
        }

        public static QueryRewriteSettingsBuilder builder() {
            return new QueryRewriteSettingsBuilder();
        }

        public static class QueryRewriteSettingsBuilder {
            private Boolean enabled;

            public QueryRewriteSettingsBuilder enabled(Boolean enabled) {
                this.enabled = enabled;
                return this;
            }

            public QueryRewriteSettings build() {
                return new QueryRewriteSettings(enabled);
            }
        }
    }

    @Setter
    @Getter
    public static class RateLimitSettings {
        private GlobalRateLimit global;

        public RateLimitSettings(GlobalRateLimit global) {
            this.global = global;
        }

        public static RateLimitSettingsBuilder builder() {
            return new RateLimitSettingsBuilder();
        }

        public static class RateLimitSettingsBuilder {
            private GlobalRateLimit global;

            public RateLimitSettingsBuilder global(GlobalRateLimit global) {
                this.global = global;
                return this;
            }

            public RateLimitSettings build() {
                return new RateLimitSettings(global);
            }
        }
    }

    @Setter
    @Getter
    public static class GlobalRateLimit {
        private Boolean enabled;
        private Integer maxConcurrent;
        private Integer maxWaitSeconds;
        private Integer leaseSeconds;
        private Integer pollIntervalMs;

        public GlobalRateLimit(Boolean enabled, Integer maxConcurrent, Integer maxWaitSeconds,
                               Integer leaseSeconds, Integer pollIntervalMs) {
            this.enabled = enabled;
            this.maxConcurrent = maxConcurrent;
            this.maxWaitSeconds = maxWaitSeconds;
            this.leaseSeconds = leaseSeconds;
            this.pollIntervalMs = pollIntervalMs;
        }

        public static GlobalRateLimitBuilder builder() {
            return new GlobalRateLimitBuilder();
        }

        public static class GlobalRateLimitBuilder {
            private Boolean enabled;
            private Integer maxConcurrent;
            private Integer maxWaitSeconds;
            private Integer leaseSeconds;
            private Integer pollIntervalMs;

            public GlobalRateLimitBuilder enabled(Boolean enabled) {
                this.enabled = enabled;
                return this;
            }

            public GlobalRateLimitBuilder maxConcurrent(Integer maxConcurrent) {
                this.maxConcurrent = maxConcurrent;
                return this;
            }

            public GlobalRateLimitBuilder maxWaitSeconds(Integer maxWaitSeconds) {
                this.maxWaitSeconds = maxWaitSeconds;
                return this;
            }

            public GlobalRateLimitBuilder leaseSeconds(Integer leaseSeconds) {
                this.leaseSeconds = leaseSeconds;
                return this;
            }

            public GlobalRateLimitBuilder pollIntervalMs(Integer pollIntervalMs) {
                this.pollIntervalMs = pollIntervalMs;
                return this;
            }

            public GlobalRateLimit build() {
                return new GlobalRateLimit(enabled, maxConcurrent, maxWaitSeconds, leaseSeconds, pollIntervalMs);
            }
        }
    }
}

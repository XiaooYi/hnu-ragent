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

package com.nageoffer.ai.ragent.rag.controller.vo;

import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 检索调试响应
 * <p>
 * 包含每个检索通道的原始召回结果、后处理链每阶段变化量和最终结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetrieveDebugVO {

    /**
     * 链路追踪 ID，与 /rag/traces/runs 中的 traceId 对应
     */
    private String traceId;

    /**
     * 检索问题
     */
    private String question;

    /**
     * 最终 TopK
     */
    private int topK;

    /**
     * 总耗时（毫秒）
     */
    private long totalLatencyMs;

    /**
     * 各通道执行详情
     */
    private List<ChannelDebugInfo> channels;

    /**
     * 多通道合并后、后处理前的总 chunk 数
     */
    private int mergedChunkCount;

    /**
     * 后处理链各阶段详情，按执行顺序排列
     */
    private List<PostProcessStageInfo> postProcessStages;

    /**
     * 最终检索结果
     */
    private List<ChunkDebugItem> finalChunks;

    /**
     * 最终结果数量
     */
    private int finalChunkCount;

    // ========== 内嵌结构 ==========

    /**
     * 单个检索通道的执行详情
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChannelDebugInfo {

        /**
         * 通道类型
         */
        private SearchChannelType channelType;

        /**
         * 通道名称
         */
        private String channelName;

        /**
         * 是否启用
         */
        private boolean enabled;

        /**
         * 未启用时的跳过原因
         */
        private String skipReason;

        /**
         * 该通道召回的 chunk 列表
         */
        private List<ChunkDebugItem> chunks;

        /**
         * 该通道召回的 chunk 数量
         */
        private int chunkCount;

        /**
         * 该通道耗时（毫秒）
         */
        private long latencyMs;

        /**
         * 扩展信息（如意图数量、collection 列表等）
         */
        private Map<String, Object> metadata;
    }

    /**
     * 后处理链单阶段详情
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class PostProcessStageInfo {

        /**
         * 阶段名称（如 Deduplication、Rerank）
         */
        private String stageName;

        /**
         * 是否启用
         */
        private boolean enabled;

        /**
         * 未启用时的跳过原因
         */
        private String skipReason;

        /**
         * 输入 chunk 数量
         */
        private int inputCount;

        /**
         * 输出 chunk 数量
         */
        private int outputCount;

        /**
         * 被移除的 chunk 数量
         */
        private int removedCount;

        /**
         * 阶段说明（如 "TopK截断: 28 → 10"）
         */
        private String description;
    }

    /**
     * 单条 Chunk 调试信息
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ChunkDebugItem {

        /**
         * Chunk 唯一标识
         */
        private String id;

        /**
         * Chunk 文本内容（已截断，前端展示用）
         */
        private String text;

        /**
         * 完整文本内容（verbose=true 时返回）
         */
        private String textFull;

        /**
         * 相关性分数
         */
        private Double score;

        /**
         * 元数据（如 kb_name, doc_name, collection 等）
         */
        private Map<String, Object> metadata;
    }
}

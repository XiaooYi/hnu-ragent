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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.controller.request.RetrieveDebugRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RetrieveDebugVO;
import com.nageoffer.ai.ragent.rag.controller.vo.RetrieveDebugVO.ChannelDebugInfo;
import com.nageoffer.ai.ragent.rag.controller.vo.RetrieveDebugVO.ChunkDebugItem;
import com.nageoffer.ai.ragent.rag.controller.vo.RetrieveDebugVO.PostProcessStageInfo;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import com.nageoffer.ai.ragent.rag.service.RAGDebugService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 检索调试服务实现
 * <p>
 * 直接注入 Spring 容器中所有 {@link SearchChannel} 和 {@link SearchResultPostProcessor}，
 * 复用生产检索的全部组件，同时捕获中间状态。
 * <p>
 * 不修改任何现有类，零侵入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGDebugServiceImpl implements RAGDebugService {

    private final List<SearchChannel> searchChannels;
    private final List<SearchResultPostProcessor> postProcessors;
    private final SearchChannelProperties searchProperties;
    private final RAGConfigProperties ragConfigProperties;
    private final Executor ragRetrievalExecutor;

    private static final int MAX_TOPK = 50;
    private static final int MAX_COLLECTION_NAMES = 20;
    private static final int TEXT_TRUNCATE_LENGTH = 500;

    // ==================== 入口 ====================

    @Override
    public RetrieveDebugVO debugRetrieve(RetrieveDebugRequest request) {
        validate(request);

        String traceId = IdUtil.getSnowflakeNextIdStr();
        long startTime = System.currentTimeMillis();
        int topK = resolveTopK(request);

        log.info("[traceId={}] 检索调试开始 - question: '{}', topK: {}, debugMode: {}, "
                        + "enableChannels: {}, enableRerank: {}, enableDedup: {}",
                traceId, request.getQuestion(), topK, request.getDebugMode(),
                request.getEnableChannels(), request.getEnableRerank(), request.getEnableDedup());

        // 构建 SearchContext（无意图信息，走正常兜底逻辑）
        SearchContext context = buildSearchContext(request, topK);

        // 并行执行通道，保留原始 RetrievedChunk + DebugInfo
        List<ChannelExecution> executions = executeChannels(context, request, traceId);

        List<ChannelDebugInfo> channelInfos = executions.stream()
                .map(ChannelExecution::debugInfo)
                .toList();

        List<SearchChannelResult> channelResults = executions.stream()
                .map(ChannelExecution::result)
                .filter(Objects::nonNull)
                .toList();

        // 合并所有通道的 chunk
        List<RetrievedChunk> mergedChunks = channelResults.stream()
                .flatMap(r -> r.getChunks().stream())
                .collect(Collectors.toList());
        log.info("[traceId={}] 多通道合并后共 {} 个 chunk（来自 {} 个启用的通道）",
                traceId, mergedChunks.size(), channelResults.size());

        // 后处理链
        List<PostProcessStageInfo> stageInfos = new ArrayList<>();
        List<RetrievedChunk> finalChunks = executePostProcessors(
                mergedChunks, channelResults, context, request, stageInfos, traceId);

        long totalLatency = System.currentTimeMillis() - startTime;

        boolean debugMode = Boolean.TRUE.equals(request.getDebugMode());

        RetrieveDebugVO vo = RetrieveDebugVO.builder()
                .traceId(traceId)
                .question(request.getQuestion())
                .topK(topK)
                .totalLatencyMs(totalLatency)
                .channels(channelInfos)
                .mergedChunkCount(mergedChunks.size())
                .postProcessStages(stageInfos)
                .finalChunks(toChunkDebugItems(finalChunks, debugMode))
                .finalChunkCount(finalChunks.size())
                .build();

        log.info("[traceId={}] 检索调试完成 - 总耗时: {}ms, 通道数: {} (启用: {}), 最终chunk数: {}",
                traceId, totalLatency, channelInfos.size(),
                channelInfos.stream().filter(ChannelDebugInfo::isEnabled).count(),
                finalChunks.size());

        return vo;
    }

    // ==================== 校验 ====================

    private void validate(RetrieveDebugRequest request) {
        if (StrUtil.isBlank(request.getQuestion())) {
            throw new IllegalArgumentException("question 不能为空");
        }
        if (request.getTopK() != null && request.getTopK() > MAX_TOPK) {
            throw new IllegalArgumentException("topK 上限为 " + MAX_TOPK);
        }
        if (request.getCollectionNames() != null && request.getCollectionNames().size() > MAX_COLLECTION_NAMES) {
            throw new IllegalArgumentException("collectionNames 上限为 " + MAX_COLLECTION_NAMES);
        }
    }

    private int resolveTopK(RetrieveDebugRequest request) {
        if (request.getTopK() != null && request.getTopK() > 0) {
            return request.getTopK();
        }
        return searchProperties.getDefaultTopK();
    }

    // ==================== SearchContext ====================

    private SearchContext buildSearchContext(RetrieveDebugRequest request, int topK) {
        SearchContext.SearchContextBuilder builder = SearchContext.builder()
                .originalQuestion(request.getQuestion())
                .rewrittenQuestion(request.getQuestion())
                .intents(List.of())  // 无意图，VectorGlobalSearchChannel 自动兜底
                .topK(topK);

        // 将 debug 特有参数放入 metadata，通道可选择性读取
        if (CollUtil.isNotEmpty(request.getCollectionNames())) {
            builder.metadata(Map.of("debugCollectionNames", request.getCollectionNames()));
        }
        if (request.getOverrideConfidenceThreshold() != null) {
            builder.metadata(Map.of("debugConfidenceThreshold", request.getOverrideConfidenceThreshold()));
        }

        return builder.build();
    }

    // ==================== 通道执行 ====================

    /**
     * 并行执行所有通道，记录启用状态、跳过原因和耗时
     */
    private List<ChannelExecution> executeChannels(SearchContext context,
                                                    RetrieveDebugRequest request,
                                                    String traceId) {
        // 按优先级排序（与生产逻辑一致）
        List<SearchChannel> sorted = searchChannels.stream()
                .sorted(Comparator.comparingInt(SearchChannel::getPriority))
                .toList();

        List<CompletableFuture<ChannelExecution>> futures = sorted.stream()
                .map(channel -> CompletableFuture.supplyAsync(
                        () -> executeSingleChannel(channel, context, request, traceId),
                        ragRetrievalExecutor
                ))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    private ChannelExecution executeSingleChannel(SearchChannel channel,
                                                   SearchContext context,
                                                   RetrieveDebugRequest request,
                                                   String traceId) {
        boolean enabled = isChannelEnabledForDebug(channel, context, request);
        if (!enabled) {
            String skipReason = buildChannelSkipReason(channel, context, request);
            log.info("[traceId={}] 通道 {} 跳过 - 原因: {}", traceId, channel.getName(), skipReason);
            return new ChannelExecution(
                    ChannelDebugInfo.builder()
                            .channelType(channel.getType())
                            .channelName(channel.getName())
                            .enabled(false)
                            .skipReason(skipReason)
                            .chunks(List.of())
                            .chunkCount(0)
                            .latencyMs(0)
                            .metadata(Map.of())
                            .build(),
                    null  // 未启用，无 SearchChannelResult
            );
        }

        long channelStart = System.currentTimeMillis();
        try {
            log.info("[traceId={}] 执行检索通道: {}", traceId, channel.getName());
            SearchChannelResult result = channel.search(context);
            long latency = System.currentTimeMillis() - channelStart;
            int chunkCount = result.getChunks().size();

            log.info("[traceId={}] 通道 {} 完成 ✓ - {} 个 chunk，耗时: {}ms",
                    traceId, channel.getName(), chunkCount, latency);

            return new ChannelExecution(
                    ChannelDebugInfo.builder()
                            .channelType(channel.getType())
                            .channelName(channel.getName())
                            .enabled(true)
                            .skipReason(null)
                            .chunks(toChunkDebugItems(result.getChunks(), Boolean.TRUE.equals(request.getDebugMode())))
                            .chunkCount(chunkCount)
                            .latencyMs(latency)
                            .metadata(result.getMetadata())
                            .build(),
                    result
            );
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - channelStart;
            log.error("[traceId={}] 通道 {} 执行失败 - 耗时: {}ms", traceId, channel.getName(), latency, e);
            return new ChannelExecution(
                    ChannelDebugInfo.builder()
                            .channelType(channel.getType())
                            .channelName(channel.getName())
                            .enabled(true)
                            .skipReason("执行异常: " + e.getMessage())
                            .chunks(List.of())
                            .chunkCount(0)
                            .latencyMs(latency)
                            .metadata(Map.of("error", e.getClass().getSimpleName()))
                            .build(),
                    SearchChannelResult.builder()
                            .channelType(channel.getType())
                            .channelName(channel.getName())
                            .chunks(List.of())
                            .latencyMs(latency)
                            .build()
            );
        }
    }

    /**
     * 合并用户显式指定的通道白名单与通道自身的 isEnabled 逻辑
     */
    private boolean isChannelEnabledForDebug(SearchChannel channel,
                                              SearchContext context,
                                              RetrieveDebugRequest request) {
        if (CollUtil.isNotEmpty(request.getEnableChannels())) {
            return request.getEnableChannels().contains(channel.getType());
        }
        return channel.isEnabled(context);
    }

    /**
     * 构造通道跳过原因
     */
    private String buildChannelSkipReason(SearchChannel channel,
                                           SearchContext context,
                                           RetrieveDebugRequest request) {
        if (CollUtil.isNotEmpty(request.getEnableChannels())) {
            return "未在 enableChannels 白名单中指定，已跳过";
        }
        // 通道自身 isEnabled 返回 false，给出通用原因
        return "通道 isEnabled() 返回 false（可能因配置关闭、无匹配意图等）";
    }

    // ==================== 后处理链 ====================

    private List<RetrievedChunk> executePostProcessors(List<RetrievedChunk> mergedChunks,
                                                        List<SearchChannelResult> channelResults,
                                                        SearchContext context,
                                                        RetrieveDebugRequest request,
                                                        List<PostProcessStageInfo> stageInfos,
                                                        String traceId) {
        List<RetrievedChunk> chunks = new ArrayList<>(mergedChunks);

        // 按 order 排序（与生产逻辑一致）
        List<SearchResultPostProcessor> sorted = postProcessors.stream()
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
                .toList();

        for (SearchResultPostProcessor processor : sorted) {
            boolean enabled = isProcessorEnabledForDebug(processor, context, request);
            if (!enabled) {
                String skipReason = buildProcessorSkipReason(processor, request);
                stageInfos.add(PostProcessStageInfo.builder()
                        .stageName(processor.getName())
                        .enabled(false)
                        .skipReason(skipReason)
                        .inputCount(chunks.size())
                        .outputCount(chunks.size())
                        .removedCount(0)
                        .description("已跳过")
                        .build());
                log.info("[traceId={}] 后处理器 {} 跳过 - 原因: {}", traceId, processor.getName(), skipReason);
                continue;
            }

            int beforeSize = chunks.size();
            try {
                chunks = processor.process(chunks, channelResults, context);
            } catch (Exception e) {
                log.error("[traceId={}] 后处理器 {} 执行失败，跳过", traceId, processor.getName(), e);
                stageInfos.add(PostProcessStageInfo.builder()
                        .stageName(processor.getName())
                        .enabled(true)
                        .skipReason("执行异常: " + e.getMessage())
                        .inputCount(beforeSize)
                        .outputCount(chunks.size())
                        .removedCount(0)
                        .description("执行失败，保留输入")
                        .build());
                continue;
            }
            int afterSize = chunks.size();
            int removed = beforeSize - afterSize;

            String description = buildStageDescription(processor.getName(), beforeSize, afterSize);

            stageInfos.add(PostProcessStageInfo.builder()
                    .stageName(processor.getName())
                    .enabled(true)
                    .inputCount(beforeSize)
                    .outputCount(afterSize)
                    .removedCount(Math.max(0, removed))
                    .description(description)
                    .build());

            log.info("[traceId={}] 后处理器 {} 完成 - 输入: {}, 输出: {}, 移除: {}",
                    traceId, processor.getName(), beforeSize, afterSize, Math.max(0, removed));
        }

        return chunks;
    }

    private boolean isProcessorEnabledForDebug(SearchResultPostProcessor processor,
                                                SearchContext context,
                                                RetrieveDebugRequest request) {
        // 用户显式控制优先
        String name = processor.getName();
        if ("Deduplication".equalsIgnoreCase(name) && request.getEnableDedup() != null) {
            return request.getEnableDedup();
        }
        if ("Rerank".equalsIgnoreCase(name) && request.getEnableRerank() != null) {
            return request.getEnableRerank();
        }
        // 否则走处理器自身的 isEnabled 逻辑
        return processor.isEnabled(context);
    }

    private String buildProcessorSkipReason(SearchResultPostProcessor processor,
                                             RetrieveDebugRequest request) {
        String name = processor.getName();
        if ("Deduplication".equalsIgnoreCase(name) && Boolean.FALSE.equals(request.getEnableDedup())) {
            return "enableDedup=false，跳过去重";
        }
        if ("Rerank".equalsIgnoreCase(name) && Boolean.FALSE.equals(request.getEnableRerank())) {
            return "enableRerank=false，跳过 Rerank";
        }
        return "处理器 isEnabled() 返回 false（可能因配置关闭）";
    }

    private String buildStageDescription(String stageName, int before, int after) {
        if ("Rerank".equalsIgnoreCase(stageName) && after < before) {
            return "TopK 截断: " + before + " → " + after;
        }
        if ("Deduplication".equalsIgnoreCase(stageName)) {
            return "去重: " + before + " → " + after + " (移除 " + (before - after) + " 条重复)";
        }
        return before + " → " + after;
    }

    // ==================== 类型转换 ====================

    /**
     * RetrievedChunk → ChunkDebugItem
     */
    private List<ChunkDebugItem> toChunkDebugItems(List<RetrievedChunk> chunks, boolean debugMode) {
        return chunks.stream()
                .map(chunk -> toChunkDebugItem(chunk, debugMode))
                .toList();
    }

    private ChunkDebugItem toChunkDebugItem(RetrievedChunk chunk, boolean debugMode) {
        String fullText = chunk.getText();
        String truncated = fullText != null && fullText.length() > TEXT_TRUNCATE_LENGTH
                ? fullText.substring(0, TEXT_TRUNCATE_LENGTH)
                : fullText;

        return ChunkDebugItem.builder()
                .id(chunk.getId())
                .text(truncated)
                .textFull(debugMode ? fullText : null)
                .score(chunk.getScore() != null ? chunk.getScore().doubleValue() : null)
                .metadata(Map.of())  // RetrievedChunk 无 per-chunk 元数据，预留
                .build();
    }

    // ==================== 内部类型 ====================

    /**
     * 通道执行结果，同时持有 DebugInfo（给前端）和 SearchChannelResult（给后处理器）
     */
    private record ChannelExecution(ChannelDebugInfo debugInfo, SearchChannelResult result) {
    }
}

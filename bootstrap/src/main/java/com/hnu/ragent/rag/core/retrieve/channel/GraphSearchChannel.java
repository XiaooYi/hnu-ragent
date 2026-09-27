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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.hnu.ragent.framework.convention.RetrievedChunk;
import com.hnu.ragent.rag.config.GraphProperties;
import com.hnu.ragent.rag.config.SearchChannelProperties;
import com.hnu.ragent.rag.core.graph.GraphEvidence;
import com.hnu.ragent.rag.core.graph.LightRagClient;
import com.hnu.ragent.rag.core.intent.NodeScore;
import com.hnu.ragent.rag.core.intent.NodeScoreFilters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识图谱检索通道
 * <p>
 * 基于 LightRAG 的图谱召回：擅长多跳关系推理与实体为中心的聚合，与向量 / 关键词互补
 * 仅当开启图谱后端（{@code rag.graph.type=lightrag}）时才注册，否则整个通道不存在，引擎自动退化为无图谱检索
 * <p>
 * 与其他通道并行执行，结果统一进 RRF 融合，通道间无先后与优先级之分
 * <p>
 * 说明：LightRAG /query 无 per-request workspace，单实例即单图，故本通道查全图后在结果侧按 file_path 归属
 * 切分；真正的子图隔离需多实例，留待后续阶段
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.graph", name = "type", havingValue = "lightrag")
public class GraphSearchChannel implements SearchChannel {

    /**
     * 定向过滤时向 LightRAG 的请求量上浮倍数
     * 结果侧过滤在 top_k 截断后再筛掉跨库证据，命中库证据可能变少，多取以补召回；全局过滤只筛残留，不上浮
     */
    private static final int FILTER_TOPK_BOOST = 3;

    private static final String MODE_GLOBAL = "global";
    private static final String MODE_INTENT = "intent";

    private final LightRagClient lightRagClient;
    private final GraphProperties graphProperties;
    private final SearchChannelProperties properties;
    private final KbCollectionProvider kbCollectionProvider;

    @Override
    public String getName() {
        return "GraphSearchChannel";
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.GRAPH;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return properties.getChannels().getGraph().isEnabled();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        try {
            List<String> collections = resolveCollections(context);
            if (CollUtil.isEmpty(collections)) {
                log.info("图谱检索未解析到目标知识库，跳过");
                return emptyResult(System.currentTimeMillis() - startTime);
            }

            int recallBudget = context.getBudget().recallBudget();
            boolean directed = !MODE_GLOBAL.equalsIgnoreCase(properties.getChannels().getGraph().getMode());
            int topK = directed ? recallBudget * FILTER_TOPK_BOOST : recallBudget;
            String queryMode = graphProperties.getLightrag().getQueryMode();

            GraphEvidence evidence = lightRagClient.retrieveByScope(
                    context.getMainQuestion(), queryMode, topK, collections);
            if (CollUtil.isNotEmpty(evidence.unmatched())) {
                log.warn("图谱检索过滤掉 {} 条无主证据（已删库残留或 file_path 解析失败），残留会挤占 top_k 名额，建议清理图谱",
                        evidence.unmatched().size());
            }
            List<RetrievedChunk> chunks = evidence.matched().size() > recallBudget
                    ? evidence.matched().subList(0, recallBudget)
                    : evidence.matched();

            long latency = System.currentTimeMillis() - startTime;
            log.info("图谱检索完成，知识库={}，检索到 {} 个 Chunk，耗时 {}ms", collections, chunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.GRAPH)
                    .channelName(getName())
                    .chunks(chunks)
                    .latencyMs(latency)
                    .build();
        } catch (Exception e) {
            log.error("图谱检索失败", e);
            return emptyResult(System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 按 mode 解析目标知识库 collection（与关键词通道同款语义）
     * global 全库 / intent 仅意图域 / both 有意图走意图域，否则回退全库
     */
    private List<String> resolveCollections(SearchContext context) {
        String mode = properties.getChannels().getGraph().getMode();
        List<String> intentCollections = extractIntentCollections(context);

        if (MODE_GLOBAL.equalsIgnoreCase(mode)) {
            return kbCollectionProvider.listActiveCollections();
        }
        if (MODE_INTENT.equalsIgnoreCase(mode)) {
            return intentCollections;
        }
        return CollUtil.isNotEmpty(intentCollections) ? intentCollections : kbCollectionProvider.listActiveCollections();
    }

    /**
     * 从意图识别结果提取 KB 意图对应的 collection 名称
     */
    private List<String> extractIntentCollections(SearchContext context) {
        if (CollUtil.isEmpty(context.getIntents())) {
            return List.of();
        }
        List<NodeScore> allScores = context.getIntents().stream()
                .flatMap(si -> si.nodeScores().stream())
                .toList();
        return NodeScoreFilters.kb(allScores).stream()
                .flatMap(ns -> ns.getNode().getEffectiveCollectionNames().stream())
                .filter(StrUtil::isNotBlank)
                .distinct()
                .toList();
    }
}

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
import com.hnu.ragent.framework.convention.RetrievedChunk;
import com.hnu.ragent.rag.config.SearchChannelProperties;
import com.hnu.ragent.rag.core.intent.NodeScore;
import com.hnu.ragent.rag.core.retrieve.RetrieveRequest;
import com.hnu.ragent.rag.core.retrieve.RetrieverService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 向量全局检索通道
 */
@Slf4j
@Component
public class VectorGlobalSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final KbCollectionProvider kbCollectionProvider;
    private final RetrieverService retrieverService;

    public VectorGlobalSearchChannel(RetrieverService retrieverService,
                                     SearchChannelProperties properties,
                                     KbCollectionProvider kbCollectionProvider) {
        this.properties = properties;
        this.kbCollectionProvider = kbCollectionProvider;
        this.retrieverService = retrieverService;
    }

    @Override
    public String getName() {
        return "VectorGlobalSearch";
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        // 检查配置是否启用
        if (!properties.getChannels().getVectorGlobal().isEnabled()) {
            return false;
        }

        // 意图定向检索关闭时，全局检索必须兜底，否则无通道可用
        if (!properties.getChannels().getIntentDirected().isEnabled()) {
            return true;
        }

        List<NodeScore> allScores = context.getIntents().stream()
                .flatMap(si -> si.nodeScores().stream())
                .toList();
        if (CollUtil.isEmpty(allScores)) {
            log.info("未识别出任何意图，启用全局检索");
            return true;
        }

        double maxScore = allScores.stream()
                .mapToDouble(NodeScore::getScore)
                .max()
                .orElse(0.0);

        double threshold = properties.getChannels().getVectorGlobal().getConfidenceThreshold();
        if (maxScore < threshold) {
            log.info("意图置信度过低（{}），启用全局检索", maxScore);
            return true;
        }

        double supplementThreshold = properties.getChannels().getVectorGlobal().getSingleIntentSupplementThreshold();
        if (allScores.size() == 1 && maxScore < supplementThreshold) {
            log.info("单一中等置信度意图（{}），启用补充全局检索", maxScore);
            return true;
        }

        return false;
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("执行向量全局检索，问题：{}", context.getMainQuestion());

            // 全库范围与其它全局检索通道同源：只取未删除知识库的 collection，不用索引/表通配
            List<String> collections = kbCollectionProvider.listActiveCollections();

            if (collections.isEmpty()) {
                log.warn("未找到任何 KB collection，跳过全局检索");
                return emptyResult(System.currentTimeMillis() - startTime);
            }

            // 取数深度只受 recallBudget 管（通道不再各自乘倍数）
            int recallBudget = context.getBudget().recallBudget();
            // 一次调用覆盖全部目标库：PG 走单条 SQL 的 IN 过滤、Milvus 在服务内部逐库合并，
            // 同一个问题只向量化一次（原先逐库 fan-out 会重复调用 embedding）
            List<RetrievedChunk> allChunks = ChunkRanking.sortedByScore(retrieverService.retrieve(
                    RetrieveRequest.builder()
                            .collectionNames(collections)
                            .query(context.getMainQuestion())
                            .topK(recallBudget)
                            .build()
            ));

            long latency = System.currentTimeMillis() - startTime;

            log.info("向量全局检索完成，检索到 {} 个 Chunk，耗时 {}ms", allChunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.VECTOR_GLOBAL)
                    .channelName(getName())
                    .chunks(allChunks)
                    .latencyMs(latency)
                    .build();

        } catch (Exception e) {
            log.error("向量全局检索失败", e);
            return emptyResult(System.currentTimeMillis() - startTime);
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.VECTOR_GLOBAL;
    }
}

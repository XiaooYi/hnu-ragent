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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel.strategy;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 意图并行检索器
 * 继承模板类，实现意图特定的检索逻辑
 */
@Slf4j
public class IntentParallelRetriever
        extends com.nageoffer.ai.ragent.rag.core.retrieve.channel.AbstractParallelRetriever<IntentParallelRetriever.IntentTask> {

    private final RetrieverService retrieverService;

    public record IntentTask(NodeScore nodeScore, int intentTopK) {
    }

    public IntentParallelRetriever(RetrieverService retrieverService,
                                   Executor executor) {
        super(executor);
        this.retrieverService = retrieverService;
    }

    /**
     * 执行并行检索（重载方法，支持动态 TopK 计算）
     */
    /**
     * 按意图并行检索：每个意图用自己的知识库集合与取数深度
     *
     * @param recallBudget 默认取数深度（节点显式 topK 优先）
     */
    public List<RetrievedChunk> executeIntentRetrieval(String question,
                                                       List<NodeScore> targets,
                                                       int recallBudget) {
        List<IntentTask> intentTasks = targets.stream()
                .map(nodeScore -> new IntentTask(
                        nodeScore,
                        resolveIntentTopK(nodeScore, recallBudget)
                ))
                .toList();
        return executeParallelRetrieval(question, intentTasks, recallBudget);
    }

    @Override
    protected List<RetrievedChunk> createRetrievalTask(String question, IntentTask task, int ignoredTopK) {
        NodeScore nodeScore = task.nodeScore();
        IntentNode node = nodeScore.getNode();
        List<String> collectionNames = node.getEffectiveCollectionNames();
        if (collectionNames.isEmpty()) {
            log.warn("意图未关联任何知识库，跳过检索 - 意图ID: {}, 意图名称: {}", node.getId(), node.getName());
            return List.of();
        }
        try {
            return retrieverService.retrieve(
                    RetrieveRequest.builder()
                            .collectionNames(collectionNames)
                            .query(question)
                            .topK(task.intentTopK())
                            .build()
            );
        } catch (Exception e) {
            log.error("意图检索失败 - 意图ID: {}, 意图名称: {}, Collections: {}, 错误: {}",
                    node.getId(), node.getName(), collectionNames, e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    protected String getTargetIdentifier(IntentTask task) {
        NodeScore nodeScore = task.nodeScore();
        IntentNode node = nodeScore.getNode();
        return String.format("意图ID: %s, 意图名称: %s", node.getId(), node.getName());
    }

    @Override
    protected String getStatisticsName() {
        return "意图检索";
    }

    /**
     * 单个意图的取数深度：节点显式配置的 topK 优先，否则用召回预算
     * <p>
     * 不再乘通道倍率：取数深度只由 {@code rag.search.scope.recall-budget} 这一个旋钮决定，
     * 节点级 topK 是显式覆写，便于个别重要主题单独加深
     */
    private int resolveIntentTopK(NodeScore nodeScore, int recallBudget) {
        if (nodeScore != null && nodeScore.getNode() != null) {
            Integer nodeTopK = nodeScore.getNode().getTopK();
            if (nodeTopK != null && nodeTopK > 0) {
                return nodeTopK;
            }
        }
        return recallBudget;
    }
}

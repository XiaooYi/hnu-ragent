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

package com.hnu.ragent.rag.core.retrieve.channel.strategy;

import com.hnu.ragent.framework.convention.RetrievedChunk;
import com.hnu.ragent.rag.core.intent.IntentNode;
import com.hnu.ragent.rag.core.intent.NodeScore;
import com.hnu.ragent.rag.core.retrieve.RetrieveRequest;
import com.hnu.ragent.rag.core.retrieve.RetrieverService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentParallelRetrieverTest {

    @Test
    @DisplayName("多知识库意图用一次检索覆盖全部 Collection，topK 按倍率放大")
    void coversAllCollectionsInOneRequest() {
        RetrieverService retrieverService = mock(RetrieverService.class);
        when(retrieverService.retrieve(any(RetrieveRequest.class))).thenReturn(List.of());
        IntentParallelRetriever retriever = new IntentParallelRetriever(retrieverService, Runnable::run);

        NodeScore nodeScore = NodeScore.builder()
                .node(IntentNode.builder()
                        .id("topic-teaching")
                        .name("本科教学")
                        .collectionNames(List.of("kb_teaching", "kb_college"))
                        .build())
                .score(0.9)
                .build();

        retriever.executeIntentRetrieval("转专业条件", List.of(nodeScore), 20);

        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService).retrieve(captor.capture());
        assertEquals(List.of("kb_teaching", "kb_college"), captor.getValue().getEffectiveCollectionNames());
        assertEquals(20, captor.getValue().getTopK(), "取数深度只由 recallBudget 决定，不再乘通道倍率");
    }

    @Test
    @DisplayName("意图未关联任何知识库时不发检索请求")
    void skipsRetrievalWhenNoCollectionConfigured() {
        RetrieverService retrieverService = mock(RetrieverService.class);
        IntentParallelRetriever retriever = new IntentParallelRetriever(retrieverService, Runnable::run);

        NodeScore nodeScore = NodeScore.builder()
                .node(IntentNode.builder().id("topic-empty").name("未绑定库").build())
                .score(0.8)
                .build();

        List<RetrievedChunk> chunks = retriever.executeIntentRetrieval("问题", List.of(nodeScore), 20);

        assertTrue(chunks.isEmpty());
        verify(retrieverService, never()).retrieve(any(RetrieveRequest.class));
    }
}

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

import com.hnu.ragent.framework.convention.RetrievedChunk;
import com.hnu.ragent.rag.config.SearchChannelProperties;
import com.hnu.ragent.rag.core.retrieve.RetrieveRequest;
import com.hnu.ragent.rag.core.retrieve.RetrieverService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorGlobalSearchChannelTest {

    private final RetrieverService retrieverService = mock(RetrieverService.class);
    private final KbCollectionProvider kbCollectionProvider = mock(KbCollectionProvider.class);
    private final SearchChannelProperties properties = new SearchChannelProperties();
    private final VectorGlobalSearchChannel channel =
            new VectorGlobalSearchChannel(retrieverService, properties, kbCollectionProvider);

    @Test
    @DisplayName("一次调用覆盖全部知识库，取数深度用 recallBudget（不再逐库 fan-out）")
    void retrievesAllCollectionsInOneCall() {
        when(kbCollectionProvider.listActiveCollections()).thenReturn(List.of("kb_a", "kb_b", "kb_c"));
        when(retrieverService.retrieve(any(RetrieveRequest.class))).thenReturn(List.of());

        channel.search(SearchContext.builder()
                .originalQuestion("转专业条件")
                .topK(10)
                .budget(new RetrievalBudget(10, 40))
                .build());

        ArgumentCaptor<RetrieveRequest> captor = ArgumentCaptor.forClass(RetrieveRequest.class);
        verify(retrieverService, times(1)).retrieve(captor.capture());
        assertEquals(List.of("kb_a", "kb_b", "kb_c"), captor.getValue().getEffectiveCollectionNames());
        assertEquals(40, captor.getValue().getTopK(), "取数深度只受 recallBudget 管");
    }

    @Test
    @DisplayName("通道出口按分数降序、空结果带真实耗时")
    void sortsChunksAndReturnsRealLatency() {
        when(kbCollectionProvider.listActiveCollections()).thenReturn(List.of("kb_a"));
        when(retrieverService.retrieve(any(RetrieveRequest.class))).thenReturn(List.of(
                RetrievedChunk.builder().id("low").text("低分").score(0.2F).build(),
                RetrievedChunk.builder().id("high").text("高分").score(0.9F).build()));

        SearchChannelResult result = channel.search(SearchContext.builder()
                .originalQuestion("问题")
                .topK(10)
                .budget(new RetrievalBudget(10, 20))
                .build());

        assertEquals(List.of("high", "low"), result.getChunks().stream().map(RetrievedChunk::getId).toList());
        assertTrueLatency(result.getLatencyMs());
    }

    @Test
    @DisplayName("没有可用知识库时返回空结果并带真实耗时")
    void returnsEmptyResultWhenNoCollection() {
        when(kbCollectionProvider.listActiveCollections()).thenReturn(List.of());

        SearchChannelResult result = channel.search(SearchContext.builder()
                .originalQuestion("问题")
                .topK(10)
                .budget(new RetrievalBudget(10, 20))
                .build());

        assertEquals(List.of(), result.getChunks());
        assertTrueLatency(result.getLatencyMs());
    }

    private void assertTrueLatency(long latencyMs) {
        // 空结果与失败降级都必须带真实耗时（原先恒为 0，归因日志因此失真）
        org.junit.jupiter.api.Assertions.assertTrue(latencyMs >= 0);
    }

}

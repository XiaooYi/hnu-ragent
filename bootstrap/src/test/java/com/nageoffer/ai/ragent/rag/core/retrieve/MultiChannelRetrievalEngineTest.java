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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiChannelRetrievalEngineTest {

    @Test
    void slowChannelDegradesToEmptyAfterChannelTimeout() {
        SearchChannel fast = channel("vector", SearchChannelType.VECTOR_GLOBAL, 0,
                result("vector", SearchChannelType.VECTOR_GLOBAL, chunk("fast", "快通道资料")));
        SearchChannel slow = channel("keyword", SearchChannelType.KEYWORD_ES, 1_000,
                result("keyword", SearchChannelType.KEYWORD_ES, chunk("slow", "慢通道资料")));

        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getChannels().setTimeoutMs(200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            long start = System.nanoTime();
            List<RetrievedChunk> chunks = engine(List.of(fast, slow), properties, pool)
                    .retrieveKnowledgeChannels(List.of(new SubQuestionIntent("问题", List.of())), 10);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(List.of("fast"), chunks.stream().map(RetrievedChunk::getId).toList(),
                    "慢通道超时按空结果降级，快通道证据保留");
            assertTrue(elapsedMs < 800, "慢通道不得钳制整次检索，实际耗时 " + elapsedMs + "ms");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failingChannelDegradesToEmptyWithoutBreakingOthers() {
        SearchChannel failing = mock(SearchChannel.class);
        when(failing.getName()).thenReturn("failing");
        when(failing.getType()).thenReturn(SearchChannelType.HYBRID);
        when(failing.isEnabled(any(SearchContext.class))).thenReturn(true);
        when(failing.search(any(SearchContext.class))).thenThrow(new IllegalStateException("后端不可用"));

        SearchChannel fast = channel("vector", SearchChannelType.VECTOR_GLOBAL, 0,
                result("vector", SearchChannelType.VECTOR_GLOBAL, chunk("fast", "快通道资料")));

        SearchChannelProperties properties = new SearchChannelProperties();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<RetrievedChunk> chunks = engine(List.of(failing, fast), properties, pool)
                    .retrieveKnowledgeChannels(List.of(new SubQuestionIntent("问题", List.of())), 10);

            assertEquals(List.of("fast"), chunks.stream().map(RetrievedChunk::getId).toList());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void timeoutDisabledKeepsWaitingForChannel() {
        SearchChannel fast = channel("vector", SearchChannelType.VECTOR_GLOBAL, 30,
                result("vector", SearchChannelType.VECTOR_GLOBAL, chunk("fast", "快通道资料")));

        SearchChannelProperties properties = new SearchChannelProperties();
        properties.getChannels().setTimeoutMs(0);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            List<RetrievedChunk> chunks = engine(List.of(fast), properties, pool)
                    .retrieveKnowledgeChannels(List.of(new SubQuestionIntent("问题", List.of())), 10);

            assertEquals(List.of("fast"), chunks.stream().map(RetrievedChunk::getId).toList());
        } finally {
            pool.shutdownNow();
        }
    }

    private MultiChannelRetrievalEngine engine(List<SearchChannel> channels,
                                               SearchChannelProperties properties,
                                               ExecutorService executor) {
        List<SearchResultPostProcessor> noProcessors = List.of();
        return new MultiChannelRetrievalEngine(channels, noProcessors, executor, properties);
    }

    private SearchChannel channel(String name, SearchChannelType type, long delayMs, SearchChannelResult result) {
        SearchChannel channel = mock(SearchChannel.class);
        when(channel.getName()).thenReturn(name);
        when(channel.getType()).thenReturn(type);
        when(channel.isEnabled(any(SearchContext.class))).thenReturn(true);
        when(channel.search(any(SearchContext.class))).thenAnswer(invocation -> {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            return result;
        });
        return channel;
    }

    private SearchChannelResult result(String name, SearchChannelType type, RetrievedChunk... chunks) {
        return SearchChannelResult.builder()
                .channelType(type)
                .channelName(name)
                .chunks(List.of(chunks))
                .build();
    }

    private RetrievedChunk chunk(String id, String text) {
        return RetrievedChunk.builder().id(id).text(text).score(0.9F).build();
    }
}

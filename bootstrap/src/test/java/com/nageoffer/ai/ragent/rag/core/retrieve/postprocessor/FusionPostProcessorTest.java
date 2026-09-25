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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FusionPostProcessorTest {

    private SearchChannelProperties properties;
    private FusionPostProcessor processor;

    @BeforeEach
    void setUp() {
        properties = new SearchChannelProperties();
        processor = new FusionPostProcessor(properties);
    }

    @Test
    void multiChannelHitsRankHigherThanSingleChannelHits() {
        // b 同时被向量与关键词命中 → RRF 分数最高；a 仅关键词、c 仅向量
        RetrievedChunk a = chunk("a");
        RetrievedChunk b = chunk("b");
        RetrievedChunk c = chunk("c");
        List<SearchChannelResult> results = List.of(
                channelResult("keyword", List.of(a, b)),
                channelResult("vector", List.of(b, c)));

        List<RetrievedChunk> fused = processor.process(List.of(a, b, c), results, context());

        assertEquals(List.of("b", "a", "c"),
                fused.stream().map(RetrievedChunk::getId).toList());
        assertTrue(fused.get(0).getScore() > fused.get(1).getScore());
    }

    @Test
    void truncatesCandidatesBeforeRerank() {
        properties.getFusion().setRerankCandidateLimit(2);
        RetrievedChunk a = chunk("a");
        RetrievedChunk b = chunk("b");
        RetrievedChunk c = chunk("c");
        List<SearchChannelResult> results = List.of(
                channelResult("keyword", List.of(a, b)),
                channelResult("vector", List.of(b, c)));

        List<RetrievedChunk> fused = processor.process(List.of(a, b, c), results, context());

        assertEquals(2, fused.size());
        assertEquals("b", fused.get(0).getId());
    }

    @Test
    void keepsOriginalOrderWhenOnlyOneChannelHit() {
        RetrievedChunk a = chunk("a");
        RetrievedChunk b = chunk("b");
        List<SearchChannelResult> results = List.of(channelResult("vector", List.of(a, b)));

        List<RetrievedChunk> fused = processor.process(List.of(a, b), results, context());

        assertEquals(List.of("a", "b"), fused.stream().map(RetrievedChunk::getId).toList());
        assertEquals(0.9f, fused.get(0).getScore());
    }

    @Test
    void disabledWhenStrategyIsNotRrf() {
        properties.getFusion().setStrategy("none");

        assertFalse(processor.isEnabled(context()));
    }

    @Test
    void enabledByDefaultRrfStrategy() {
        assertTrue(processor.isEnabled(context()));
        assertEquals(5, processor.getOrder());
    }

    private RetrievedChunk chunk(String id) {
        return new RetrievedChunk(id, "text-" + id, 0.9f);
    }

    private SearchChannelResult channelResult(String name, List<RetrievedChunk> chunks) {
        return SearchChannelResult.builder()
                .channelType(SearchChannelType.KEYWORD_ES)
                .channelName(name)
                .chunks(chunks)
                .build();
    }

    private SearchContext context() {
        return SearchContext.builder()
                .originalQuestion("转专业需要什么条件")
                .topK(10)
                .build();
    }
}

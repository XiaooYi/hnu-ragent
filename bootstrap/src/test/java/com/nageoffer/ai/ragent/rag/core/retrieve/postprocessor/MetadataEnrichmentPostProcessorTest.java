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
import com.nageoffer.ai.ragent.knowledge.service.impl.ChunkMetadataResolver;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetadataEnrichmentPostProcessorTest {

    private final ChunkMetadataResolver resolver = mock(ChunkMetadataResolver.class);

    @Test
    @DisplayName("只富化不重排：补齐文档归属且保持输入顺序")
    void enrichesMetadataWithoutReordering() {
        RetrievedChunk first = chunk("c1");
        RetrievedChunk second = chunk("c2");
        RetrievedChunk unknown = chunk("c3");
        when(resolver.resolve(any())).thenReturn(java.util.Map.of(
                "c1", new ChunkMetadataResolver.ChunkMeta("doc-1", 3, "制度.pdf"),
                "c2", new ChunkMetadataResolver.ChunkMeta("doc-2", 0, "细则.pdf")));

        List<RetrievedChunk> result = processor(true).process(List.of(first, second, unknown), List.of(), context());

        assertEquals(List.of("c1", "c2", "c3"), result.stream().map(RetrievedChunk::getId).toList());
        assertEquals("doc-1", result.get(0).getDocId());
        assertEquals(3, result.get(0).getChunkIndex());
        assertEquals("制度.pdf", result.get(0).getDocName());
        assertEquals("doc-2", result.get(1).getDocId());
        assertNull(result.get(2).getDocId(), "回表未命中的分块保持原值，不报错");
    }

    @Test
    @DisplayName("开关关闭时不参与处理链")
    void disabledByConfig() {
        MetadataEnrichmentPostProcessor processor = processor(false);

        assertFalse(processor.isEnabled(context()));
        assertEquals(20, processor.getOrder());
    }

    @Test
    @DisplayName("回表失败时退回未富化结果，不影响整轮问答")
    void resolverFailureKeepsChunksUnchanged() {
        when(resolver.resolve(any())).thenThrow(new IllegalStateException("DB 抖动"));
        RetrievedChunk chunk = chunk("c1");

        List<RetrievedChunk> result = processor(true).process(List.of(chunk), List.of(), context());

        assertEquals(1, result.size());
        assertNull(result.get(0).getDocId());
    }

    private MetadataEnrichmentPostProcessor processor(boolean enabled) {
        RAGConfigProperties properties = new RAGConfigProperties();
        properties.setContextEnrichEnabled(enabled);
        return new MetadataEnrichmentPostProcessor(resolver, properties);
    }

    private RetrievedChunk chunk(String id) {
        return RetrievedChunk.builder().id(id).text("正文-" + id).score(0.9F).build();
    }

    private SearchContext context() {
        return SearchContext.builder().originalQuestion("转专业条件").topK(10).build();
    }
}

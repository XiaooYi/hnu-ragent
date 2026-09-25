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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.rag.core.vector.keyword.KeywordIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class KeywordSyncingVectorStoreServiceTest {

    private static final String COLLECTION = "hnu_default_store";
    private static final String DOC_ID = "doc-1";

    private VectorStoreService delegate;
    private KeywordIndexService keywordIndexService;
    private KeywordSyncingVectorStoreService service;

    @BeforeEach
    void setUp() {
        delegate = mock(VectorStoreService.class);
        keywordIndexService = mock(KeywordIndexService.class);
        service = new KeywordSyncingVectorStoreService(delegate, keywordIndexService);
    }

    @Test
    void indexSyncsKeywordIndexAfterVectorWrite() {
        VectorChunk chunk = VectorChunk.builder().chunkId("chunk-1").content("内容").build();

        service.indexDocumentChunks(COLLECTION, DOC_ID, List.of(chunk));

        verify(delegate).indexDocumentChunks(COLLECTION, DOC_ID, List.of(chunk));
        verify(keywordIndexService).indexDocumentChunks(COLLECTION, DOC_ID, List.of(chunk));
    }

    @Test
    void keywordFailureDoesNotBreakVectorWrite() {
        VectorChunk chunk = VectorChunk.builder().chunkId("chunk-1").content("内容").build();
        doThrow(new IllegalStateException("ES 不可用"))
                .when(keywordIndexService).indexDocumentChunks(COLLECTION, DOC_ID, List.of(chunk));

        assertDoesNotThrow(() -> service.indexDocumentChunks(COLLECTION, DOC_ID, List.of(chunk)));

        verify(delegate).indexDocumentChunks(COLLECTION, DOC_ID, List.of(chunk));
    }

    @Test
    void deletePathsPropagateToKeywordIndex() {
        service.deleteDocumentVectors(COLLECTION, DOC_ID);
        service.deleteChunkById(COLLECTION, "chunk-1");
        service.deleteChunksByIds(COLLECTION, List.of("chunk-1", "chunk-2"));

        verify(delegate).deleteDocumentVectors(COLLECTION, DOC_ID);
        verify(keywordIndexService).deleteDocumentIndex(COLLECTION, DOC_ID);
        verify(delegate).deleteChunkById(COLLECTION, "chunk-1");
        verify(keywordIndexService).deleteChunkById(COLLECTION, "chunk-1");
        verify(delegate).deleteChunksByIds(COLLECTION, List.of("chunk-1", "chunk-2"));
        verify(keywordIndexService).deleteChunksByIds(COLLECTION, List.of("chunk-1", "chunk-2"));
        verify(keywordIndexService, never()).deleteByCollection(COLLECTION);
    }
}

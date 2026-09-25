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

package com.nageoffer.ai.ragent.knowledge.mq;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeBaseCleanupEvent;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.keyword.KeywordIndexService;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseCleanupConsumerTest {

    private static final String COLLECTION = "hnu_default_store";

    private final VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
    private final FileStorageService fileStorageService = mock(FileStorageService.class);
    private final KeywordIndexService keywordIndexService = mock(KeywordIndexService.class);

    @Test
    @DisplayName("启用关键词索引时三类底层资源都被清理")
    void cleansAllResources() {
        KnowledgeBaseCleanupConsumer consumer = consumer(keywordIndexService);

        consumer.onMessage(wrapper());

        verify(vectorStoreAdmin).dropVectorSpace(COLLECTION);
        verify(fileStorageService).deleteBucket(COLLECTION);
        verify(keywordIndexService).deleteByCollection(COLLECTION);
    }

    @Test
    @DisplayName("未启用关键词索引（type=none）时不因缺少实现而失败")
    void toleratesMissingKeywordIndex() {
        KnowledgeBaseCleanupConsumer consumer = consumer(null);

        assertDoesNotThrow(() -> consumer.onMessage(wrapper()));

        verify(vectorStoreAdmin).dropVectorSpace(COLLECTION);
        verify(fileStorageService).deleteBucket(COLLECTION);
    }

    @Test
    @DisplayName("单项失败不影响其它项，但必须抛异常触发重试")
    void keepsCleaningOthersAndFailsForRetry() {
        doThrow(new IllegalStateException("对象存储抖动")).when(fileStorageService).deleteBucket(COLLECTION);
        KnowledgeBaseCleanupConsumer consumer = consumer(keywordIndexService);

        assertThrows(ServiceException.class, () -> consumer.onMessage(wrapper()));

        verify(vectorStoreAdmin).dropVectorSpace(COLLECTION);
        verify(keywordIndexService).deleteByCollection(COLLECTION);
    }

    @Test
    @DisplayName("事件缺少 collectionName 时跳过，不去删别人的数据")
    void skipsEventWithoutCollection() {
        KnowledgeBaseCleanupConsumer consumer = consumer(keywordIndexService);
        MessageWrapper<KnowledgeBaseCleanupEvent> message = MessageWrapper.<KnowledgeBaseCleanupEvent>builder()
                .body(KnowledgeBaseCleanupEvent.builder().kbId("kb-1").build())
                .build();

        assertDoesNotThrow(() -> consumer.onMessage(message));

        verify(vectorStoreAdmin, never()).dropVectorSpace(org.mockito.ArgumentMatchers.any());
        verify(fileStorageService, never()).deleteBucket(org.mockito.ArgumentMatchers.any());
        verify(keywordIndexService, never()).deleteByCollection(org.mockito.ArgumentMatchers.any());
    }

    @SuppressWarnings("unchecked")
    private KnowledgeBaseCleanupConsumer consumer(KeywordIndexService keywordIndex) {
        ObjectProvider<KeywordIndexService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(keywordIndex);
        return new KnowledgeBaseCleanupConsumer(vectorStoreAdmin, fileStorageService, provider);
    }

    private MessageWrapper<KnowledgeBaseCleanupEvent> wrapper() {
        return MessageWrapper.<KnowledgeBaseCleanupEvent>builder()
                .body(KnowledgeBaseCleanupEvent.builder()
                        .kbId("kb-1")
                        .collectionName(COLLECTION)
                        .operator("admin")
                        .build())
                .build();
    }
}

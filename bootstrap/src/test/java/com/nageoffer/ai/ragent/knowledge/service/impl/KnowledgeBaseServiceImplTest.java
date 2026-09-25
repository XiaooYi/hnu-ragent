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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.framework.mq.producer.MessageQueueProducer;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 创建知识库只依赖存储抽象（不再直接使用 S3 SDK）
 */
class KnowledgeBaseServiceImplTest {

    private final KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
    private final KnowledgeDocumentMapper knowledgeDocumentMapper = mock(KnowledgeDocumentMapper.class);
    private final VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
    private final FileStorageService fileStorageService = mock(FileStorageService.class);
    private final MessageQueueProducer messageQueueProducer = mock(MessageQueueProducer.class);
    private final BizChangeLogContext bizChangeLogContext = new BizChangeLogContext(new ObjectMapper());

    private KnowledgeBaseServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseServiceImpl(
                knowledgeBaseMapper, knowledgeDocumentMapper, vectorStoreAdmin, fileStorageService, messageQueueProducer,
                bizChangeLogContext);
        UserContext.set(LoginUser.builder().userId("u-1").username("admin").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        bizChangeLogContext.clear();
    }

    @Test
    @DisplayName("创建知识库按 collectionName 建桶并确保向量空间")
    void createsBucketThroughStorageAbstraction() {
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(0L);
        when(knowledgeBaseMapper.insert(any(KnowledgeBaseDO.class))).thenAnswer(invocation -> {
            KnowledgeBaseDO kb = invocation.getArgument(0);
            kb.setId("kb-1");
            return 1;
        });

        service.create(request());

        verify(fileStorageService).createBucket("kb_teaching");
        verify(vectorStoreAdmin).ensureVectorSpace(any());
    }

    @Test
    @DisplayName("存储建桶失败时抛业务异常，不继续创建向量空间")
    void failsWhenBucketCreationFails() {
        when(knowledgeBaseMapper.selectCount(any())).thenReturn(0L);
        when(knowledgeBaseMapper.insert(any(KnowledgeBaseDO.class))).thenReturn(1);
        doThrow(new ServiceException("存储桶名称已被占用")).when(fileStorageService).createBucket("kb_teaching");

        assertThrows(ServiceException.class, () -> service.create(request()));

        verifyNoInteractions(vectorStoreAdmin);
    }

    private KnowledgeBaseCreateRequest request() {
        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName("本科教学制度");
        request.setCollectionName("kb_teaching");
        request.setEmbeddingModel("qwen3.7-text-embedding");
        return request;
    }
}

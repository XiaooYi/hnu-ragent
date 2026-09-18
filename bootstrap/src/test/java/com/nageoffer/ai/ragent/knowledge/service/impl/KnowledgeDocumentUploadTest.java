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

import com.nageoffer.ai.ragent.core.parser.DocumentParser;
import com.nageoffer.ai.ragent.core.parser.DocumentParserSelector;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.dto.StoredFileDTO;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentUploadTest {

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private DocumentParserSelector parserSelector;
    @Mock
    private DocumentParser parser;
    @Mock
    private FileStorageService fileStorageService;
    @InjectMocks
    private KnowledgeDocumentServiceImpl service;

    private final MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", new byte[]{1});
    private KnowledgeDocumentUploadRequest request;

    @BeforeEach
    void setUp() {
        when(knowledgeBaseMapper.selectById("kb")).thenReturn(
                KnowledgeBaseDO.builder().collectionName("collection").build());
        request = new KnowledgeDocumentUploadRequest();
        request.setSourceType("file");
        request.setProcessMode("chunk");
        request.setChunkStrategy("fixed_size");
    }

    private void stubStorage() {
        when(fileStorageService.upload("collection", file)).thenReturn(StoredFileDTO.builder()
                .url("stored-url").originalFilename("notes.txt").detectedType("txt")
                .mimeType("text/plain").size(1L).build());
    }

    @Test
    void successfulUploadKeepsFileAndCreatesPendingDocument() {
        stubStorage();
        when(parserSelector.selectByMimeType("text/plain")).thenReturn(parser);

        service.upload("kb", request, file);

        ArgumentCaptor<KnowledgeDocumentDO> document = ArgumentCaptor.forClass(KnowledgeDocumentDO.class);
        verify(documentMapper).insert(document.capture());
        assertEquals("pending", document.getValue().getStatus());
        assertEquals("stored-url", document.getValue().getFileUrl());
        verify(fileStorageService, never()).deleteByUrl(anyString());
    }

    @Test
    void unsupportedMimeDeletesStoredFileWithoutInsertingDocument() {
        stubStorage();

        assertThrows(ClientException.class, () -> service.upload("kb", request, file));

        verify(fileStorageService).deleteByUrl("stored-url");
        verifyNoInteractions(documentMapper);
    }

    @Test
    void databaseFailureDeletesStoredFile() {
        stubStorage();
        when(parserSelector.selectByMimeType("text/plain")).thenReturn(parser);
        RuntimeException failure = new IllegalStateException("database unavailable");
        when(documentMapper.insert(any(KnowledgeDocumentDO.class))).thenThrow(failure);

        assertSame(failure, assertThrows(RuntimeException.class, () -> service.upload("kb", request, file)));

        verify(fileStorageService).deleteByUrl("stored-url");
    }

    @Test
    void cleanupFailurePreservesOriginalError() {
        stubStorage();
        doThrow(new IllegalStateException("storage unavailable")).when(fileStorageService).deleteByUrl("stored-url");

        ClientException error = assertThrows(ClientException.class, () -> service.upload("kb", request, file));

        assertEquals("暂不支持的文件类型：txt", error.getMessage());
        verifyNoInteractions(documentMapper);
    }

    @Test
    void invalidPipelineIsRejectedBeforeStoringFile() {
        request.setProcessMode("pipeline");

        assertThrows(ClientException.class, () -> service.upload("kb", request, file));

        verifyNoInteractions(fileStorageService, documentMapper);
    }
}

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

package com.hnu.ragent.rag.core.source;

import com.hnu.ragent.framework.convention.RetrievedChunk;
import com.hnu.ragent.framework.convention.SourceRef;
import com.hnu.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.hnu.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourcesAssemblerTest {

    private final KnowledgeDocumentMapper documentMapper = mock(KnowledgeDocumentMapper.class);
    private final SourcesAssembler assembler = new SourcesAssembler(documentMapper);

    @Test
    @DisplayName("同一文档的多个片段只产生一条来源，摘录取最高分片段，编号从 1 递增")
    void deduplicatesByDocumentAndKeepsBestExcerpt() {
        RetrievedChunk weak = chunk("c1", "弱相关片段", 0.5F, "doc-1", "转专业管理办法.pdf");
        RetrievedChunk strong = chunk("c2", "强相关片段：申请条件为……", 0.9F, "doc-1", "转专业管理办法.pdf");
        RetrievedChunk other = chunk("c3", "另一份文件", 0.7F, "doc-2", "奖助学金细则.docx");
        when(documentMapper.selectBatchIds(any())).thenReturn(List.of());

        List<SourceRef> sources = assembler.assemble(Map.of("topic-1", List.of(weak, strong, other)));

        assertEquals(2, sources.size());
        assertEquals(1, sources.get(0).getIndex());
        assertEquals("doc-1", sources.get(0).getDocId());
        assertEquals("转专业管理办法.pdf", sources.get(0).getDocName());
        assertTrue(sources.get(0).getExcerpt().startsWith("强相关片段"));
        assertEquals(2, sources.get(1).getIndex());
        assertEquals("doc-2", sources.get(1).getDocId());
    }

    @Test
    @DisplayName("无文档归属的片段不产生来源，全空时返回空列表")
    void ignoresChunksWithoutDocument() {
        RetrievedChunk anonymous = RetrievedChunk.builder().id("c1").text("无来源片段").score(0.9F).build();

        assertTrue(assembler.assemble(Map.of("topic-1", List.of(anonymous))).isEmpty());
        assertTrue(assembler.assemble(Map.of()).isEmpty());
    }

    @Test
    @DisplayName("url/feishu 来源携带外部链接，本地文件来源 url 为空并带出文件类型")
    void resolvesSourceTypeAndUrl() {
        RetrievedChunk fileChunk = chunk("c1", "本地文件", 0.9F, "doc-file", "制度.pdf");
        RetrievedChunk urlChunk = chunk("c2", "网页资料", 0.8F, "doc-url", "网页资料");
        when(documentMapper.selectBatchIds(any())).thenReturn(List.of(
                document("doc-file", "制度.pdf", "file", "pdf", "/uploads/制度.pdf"),
                document("doc-url", "网页资料", "url", null, "https://www.hnu.edu.cn/notice")));

        List<SourceRef> sources = assembler.assemble(Map.of("topic-1", List.of(fileChunk, urlChunk)));

        SourceRef fileSource = sources.get(0);
        assertEquals("file", fileSource.getSourceType());
        assertEquals("pdf", fileSource.getFileType());
        assertNull(fileSource.getUrl(), "本地文件走 docId 预览，不带外链");

        SourceRef urlSource = sources.get(1);
        assertEquals("url", urlSource.getSourceType());
        assertEquals("https://www.hnu.edu.cn/notice", urlSource.getUrl());
    }

    @Test
    @DisplayName("摘录超长时截断，且来源条数不超过上限")
    void truncatesExcerptAndCapsSourceCount() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            longText.append("很长的正文内容");
        }
        RetrievedChunk chunk = chunk("c1", longText.toString(), 0.9F, "doc-1", "长文档.pdf");
        when(documentMapper.selectBatchIds(any())).thenReturn(List.of());

        List<SourceRef> sources = assembler.assemble(Map.of("topic-1", List.of(chunk)));

        assertEquals(1, sources.size());
        String excerpt = sources.get(0).getExcerpt();
        // 上限 100 字，hutool 截断后追加省略号，总长不超过 103
        assertTrue(excerpt.length() <= 103, "摘录需按上限截断，实际长度 " + excerpt.length());
        assertTrue(excerpt.length() < longText.length(), "摘录必须短于原文");
    }

    private RetrievedChunk chunk(String id, String text, Float score, String docId, String docName) {
        return RetrievedChunk.builder()
                .id(id)
                .text(text)
                .score(score)
                .docId(docId)
                .docName(docName)
                .build();
    }

    private KnowledgeDocumentDO document(String id, String docName, String sourceType, String fileType, String location) {
        return KnowledgeDocumentDO.builder()
                .id(id)
                .docName(docName)
                .sourceType(sourceType)
                .fileType(fileType)
                .sourceLocation(location)
                .build();
    }
}

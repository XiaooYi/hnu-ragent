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

import com.hnu.ragent.framework.convention.GroundingChunk;
import com.hnu.ragent.framework.convention.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 推荐追问 grounding 片段装配：文档去重取最高分 + 条数/长度预算
 */
class GroundingChunksAssemblerTest {

    private final GroundingChunksAssembler assembler = new GroundingChunksAssembler();

    @Test
    @DisplayName("同一文档只取最高分片段，并按分数降序排列")
    void keepsBestChunkPerDocument() {
        List<GroundingChunk> chunks = assembler.assemble(Map.of("topic-1", List.of(
                chunk("c1", "弱片段", 0.5F, "doc-1", "制度.pdf"),
                chunk("c2", "强片段", 0.9F, "doc-1", "制度.pdf"),
                chunk("c3", "另一文档", 0.7F, "doc-2", "细则.pdf"))));

        assertEquals(2, chunks.size());
        assertEquals("强片段", chunks.get(0).getText());
        assertEquals("制度.pdf", chunks.get(0).getDocName());
        assertEquals("细则.pdf", chunks.get(1).getDocName());
    }

    @Test
    @DisplayName("最多 8 条、单条不超过 1200 字、总量不超过 6000 字")
    void enforcesBudgets() {
        List<RetrievedChunk> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(chunk("c" + i, "长文本".repeat(900), 1.0F - i * 0.01F, "doc-" + i, "文档" + i));
        }

        List<GroundingChunk> chunks = assembler.assemble(Map.of("topic-1", many));

        assertTrue(chunks.size() <= 8, "条数上限 8");
        assertTrue(chunks.stream().allMatch(c -> c.getText().length() <= 1200), "单条上限 1200 字");
        assertTrue(chunks.stream().mapToInt(c -> c.getText().length()).sum() <= 6000, "总量上限 6000 字");
    }

    @Test
    @DisplayName("缺 docId 或文本为空的片段不参与 grounding，空输入返回空列表")
    void skipsInvalidChunks() {
        RetrievedChunk noDoc = RetrievedChunk.builder().id("c1").text("文本").score(0.9F).build();
        RetrievedChunk noText = RetrievedChunk.builder().id("c2").text("   ").score(0.8F).docId("doc-2").build();

        assertTrue(assembler.assemble(Map.of("topic-1", List.of(noDoc, noText))).isEmpty());
        assertTrue(assembler.assemble(Map.of()).isEmpty());
    }

    @Test
    @DisplayName("缺少 docName 时回退用 docId 作为文档标识")
    void fallsBackToDocIdWhenNameMissing() {
        List<GroundingChunk> chunks = assembler.assemble(Map.of("topic-1",
                List.of(chunk("c1", "片段", 0.9F, "doc-1", null))));

        assertEquals("doc-1", chunks.get(0).getDocName());
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

    @Test
    @DisplayName("多意图输入按文档合并")
    void mergesAcrossIntents() {
        Map<String, List<RetrievedChunk>> byIntent = new LinkedHashMap<>();
        byIntent.put("topic-1", List.of(chunk("c1", "片段A", 0.9F, "doc-1", "A.pdf")));
        byIntent.put("topic-2", List.of(chunk("c2", "片段B", 0.8F, "doc-2", "B.pdf")));

        assertEquals(2, assembler.assemble(byIntent).size());
    }
}

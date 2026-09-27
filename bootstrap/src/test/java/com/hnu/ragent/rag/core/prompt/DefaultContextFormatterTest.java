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

package com.hnu.ragent.rag.core.prompt;

import com.hnu.ragent.framework.convention.RetrievedChunk;
import com.hnu.ragent.rag.core.intent.IntentNode;
import com.hnu.ragent.rag.core.intent.NodeScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文按文档聚合渲染
 */
class DefaultContextFormatterTest {

    private final DefaultContextFormatter formatter =
            new DefaultContextFormatter(new PromptTemplateLoader(new DefaultResourceLoader()));

    @Test
    @DisplayName("同一文档的多个分块合成一个 content 块，块内按序号升序")
    void groupsChunksByDocumentAndOrdersByChunkIndex() {
        RetrievedChunk laterChunk = chunk("c2", "第二段", 0.9F, "doc-1", 1, "转专业管理办法.pdf");
        RetrievedChunk earlierChunk = chunk("c1", "第一段", 0.8F, "doc-1", 0, "转专业管理办法.pdf");
        RetrievedChunk otherDocChunk = chunk("c3", "奖助学金说明", 0.7F, "doc-2", 0, "奖助学金细则.docx");

        String context = formatter.formatKbContext(
                List.of(intentScore("topic-1")),
                Map.of("topic-1", List.of(laterChunk, otherDocChunk, earlierChunk)),
                10);

        assertTrue(context.contains("<content data-ragent-doc-id=\"doc-1\">"), context);
        assertTrue(context.contains("<content data-ragent-doc-id=\"doc-2\">"), context);
        int firstDocPosition = context.indexOf("data-ragent-doc-id=\"doc-1\"");
        int secondDocPosition = context.indexOf("data-ragent-doc-id=\"doc-2\"");
        assertTrue(firstDocPosition < secondDocPosition, "文档之间按最佳命中块的顺序排列");
        assertFalse(context.contains("转专业管理办法"), "文档标题不得进入模型上下文，避免模型写出「出自《XX》」");
        assertTrue(context.indexOf("第一段") < context.indexOf("第二段"), "文档内部按 chunkIndex 升序还原原文顺序");
        assertEquals(2, countOccurrences(context, "<content"), context);
    }

    @Test
    @DisplayName("缺少文档归属的分块独立成块，内容不丢失且保留相关性位置")
    void keepsUntitledChunksAsStandaloneBlocks() {
        RetrievedChunk withDoc = chunk("c1", "有来源的内容", 0.9F, "doc-1", 0, "制度.pdf");
        RetrievedChunk withoutDoc = chunk("c2", "没有来源的内容", 0.8F, null, null, null);

        String context = formatter.formatKbContext(
                List.of(intentScore("topic-1")),
                Map.of("topic-1", List.of(withDoc, withoutDoc)),
                10);

        assertTrue(context.contains("有来源的内容"), context);
        assertTrue(context.contains("没有来源的内容"), context);
        assertTrue(context.contains("<content>\n没有来源的内容"), context);
    }

    @Test
    @DisplayName("内部 docId 会清洗破坏标签属性的字符，标题不进入上下文")
    void sanitizesDocumentIdAttribute() {
        RetrievedChunk chunk = chunk("c1", "内容", 0.9F, "doc-1", 0, "《\"><转专业>》.pdf");

        String context = formatter.formatKbContext(
                List.of(intentScore("topic-1")),
                Map.of("topic-1", List.of(chunk)),
                10);

        assertTrue(context.contains("<content data-ragent-doc-id=\"doc-1\">"), context);
        assertFalse(context.contains("转专业"), "文档名不得泄漏进上下文: " + context);
    }

    @Test
    @DisplayName("多意图合并时按 chunk id 去重，不重复灌入同一分块")
    void deduplicatesSameChunkAcrossIntents() {
        RetrievedChunk shared = chunk("c1", "共享段落", 0.9F, "doc-1", 0, "制度.pdf");

        String context = formatter.formatKbContext(
                List.of(intentScore("topic-1"), intentScore("topic-2")),
                Map.of(
                        "topic-1", List.of(shared),
                        "topic-2", List.of(shared)),
                10);

        assertEquals(1, countOccurrences(context, "共享段落"), context);
    }

    private NodeScore intentScore(String id) {
        IntentNode node = IntentNode.builder().id(id).name(id).promptSnippet("按资料作答").build();
        return NodeScore.builder().node(node).score(0.9).build();
    }

    private RetrievedChunk chunk(String id, String text, Float score, String docId,
                                 Integer chunkIndex, String docName) {
        return RetrievedChunk.builder()
                .id(id)
                .text(text)
                .score(score)
                .docId(docId)
                .chunkIndex(chunkIndex)
                .docName(docName)
                .build();
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}

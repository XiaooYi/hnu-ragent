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

package com.nageoffer.ai.ragent.rag.core.source;

import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationContextEnricherTest {

    private static final String CONTEXT = """
            <documents>
            <content data-ragent-doc-id="doc-1">
            第一份资料
            </content>
            <content data-ragent-doc-id="doc-2">
            第二份资料
            </content>
            <content>
            无来源资料
            </content>
            </documents>
            """;

    @Test
    @DisplayName("开启引用时按来源编号注入 ref，并抹掉内部 docId")
    void injectsRefWhenCitationEnabled() {
        String enriched = enricher(true).enrich(CONTEXT, sources());

        assertTrue(enriched.contains("<content ref=\"1\">"), enriched);
        assertTrue(enriched.contains("<content ref=\"2\">"), enriched);
        assertFalse(enriched.contains("data-ragent-doc-id"), "内部 docId 不得泄漏给模型");
        assertTrue(enriched.contains("无来源资料"), "无归属资料保持原样");
    }

    @Test
    @DisplayName("关闭引用时只抹掉内部 docId，不注入编号")
    void stripsInternalAnchorWhenCitationDisabled() {
        String enriched = enricher(false).enrich(CONTEXT, sources());

        assertFalse(enriched.contains("data-ragent-doc-id"), enriched);
        assertFalse(enriched.contains("ref="), enriched);
        assertTrue(enriched.contains("第一份资料"));
    }

    @Test
    @DisplayName("来源列表里没有的 docId 不注入编号")
    void skipsUnknownDocument() {
        String enriched = enricher(true).enrich(CONTEXT, List.of());

        assertFalse(enriched.contains("ref="), enriched);
        assertFalse(enriched.contains("data-ragent-doc-id"), enriched);
    }

    @Test
    @DisplayName("空上下文直接返回，不做任何替换")
    void handlesBlankContext() {
        assertEquals("", enricher(true).enrich(null, sources()));
        assertTrue(enricher(true).enrich("   ", sources()).isBlank());
    }

    private CitationContextEnricher enricher(boolean citationEnabled) {
        RAGConfigProperties properties = new RAGConfigProperties();
        properties.setCitationEnabled(citationEnabled);
        return new CitationContextEnricher(properties);
    }

    private List<SourceRef> sources() {
        return List.of(
                SourceRef.builder().index(1).docId("doc-1").build(),
                SourceRef.builder().index(2).docId("doc-2").build());
    }
}

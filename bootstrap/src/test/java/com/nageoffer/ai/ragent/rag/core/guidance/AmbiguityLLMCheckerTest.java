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

package com.nageoffer.ai.ragent.rag.core.guidance;

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 歧义确认的降级语义：只读流程判不出来就放行，不能因模型异常反复阻断用户
 */
class AmbiguityLLMCheckerTest {

    private final LLMService llmService = mock(LLMService.class);
    private final PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);

    private AmbiguityLLMChecker checker;

    @BeforeEach
    void setUp() {
        when(promptTemplateLoader.render(anyString(), anyMap())).thenReturn("歧义确认提示词");
        checker = new AmbiguityLLMChecker(llmService, promptTemplateLoader);
    }

    @Test
    @DisplayName("正常响应按 ambiguous 字段判定")
    void parsesAmbiguousField() {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("{\"ambiguous\":true}");
        assertTrue(checker.checkAmbiguity("转专业", ranked()));

        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("{\"ambiguous\":false}");
        assertFalse(checker.checkAmbiguity("转专业", ranked()));
    }

    @Test
    @DisplayName("响应畸形 / 缺字段 / 调用异常一律放行（跳过澄清）")
    void degradesToSkipClarification() {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("我不确定");
        assertFalse(checker.checkAmbiguity("转专业", ranked()));

        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("{\"reason\":\"x\"}");
        assertFalse(checker.checkAmbiguity("转专业", ranked()));

        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenThrow(new IllegalStateException("模型不可用"));
        assertFalse(checker.checkAmbiguity("转专业", ranked()));
    }

    @Test
    @DisplayName("候选文本包含意图 ID、完整路径与说明")
    void rendersCandidatesWithFullPath() {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("{\"ambiguous\":false}");

        checker.checkAmbiguity("转专业", ranked());

        ArgumentCaptor<Map<String, String>> slots = ArgumentCaptor.forClass(Map.class);
        verify(promptTemplateLoader).render(anyString(), slots.capture());
        String candidates = slots.getValue().get("candidates");
        assertTrue(candidates.contains("意图ID: u-topic"), candidates);
        assertTrue(candidates.contains("完整路径: 湖大制度 > 本科教学 > 转专业"), candidates);
        assertTrue(candidates.contains("说明: 本科生转专业条件"), candidates);
    }

    private List<NodeScore> ranked() {
        return List.of(
                NodeScore.builder().node(node("u-topic", "转专业", "本科生转专业条件")).score(0.9).build(),
                NodeScore.builder().node(node("p-topic", "转专业", "研究生转专业条件")).score(0.8).build());
    }

    private IntentNode node(String id, String name, String description) {
        return IntentNode.builder()
                .id(id)
                .name(name)
                .description(description)
                .fullPath("湖大制度 > 本科教学 > " + name)
                .build();
    }
}

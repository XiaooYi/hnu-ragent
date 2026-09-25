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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.GroundingChunk;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dto.RecommendedQuestionsPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 推荐追问生成：解析健壮性 + 三态结局 + grounding 注入
 */
class RecommendedQuestionGeneratorTest {

    private final PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
    private final LLMService llmService = mock(LLMService.class);

    private RecommendedQuestionGenerator generator;

    @BeforeEach
    void setUp() {
        when(promptTemplateLoader.render(anyString(), anyMap())).thenReturn("渲染后的提示词");
        generator = new RecommendedQuestionGenerator(promptTemplateLoader, llmService);
    }

    @Test
    @DisplayName("带代码围栏的响应可解析，去重并只取前 3 条")
    void parsesFencedJsonAndDeduplicates() {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn(
                "```json\n[\"转专业学分要求是多少\",\" 转专业学分要求是多少 \",\"材料交到哪\",\"多久出结果\",\"第四个问题\"]\n```");

        RecommendedQuestionsPayload payload = generator.generate("转专业", "回答内容", List.of());

        assertEquals(RecommendedQuestionsPayload.Status.SUCCESS, payload.status());
        assertEquals(List.of("转专业学分要求是多少", "材料交到哪", "多久出结果"), payload.questions());
    }

    @Test
    @DisplayName("空数组视为已生成但无合适追问（负缓存）")
    void emptyArrayYieldsEmptyStatus() {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("[]");

        assertEquals(RecommendedQuestionsPayload.Status.EMPTY,
                generator.generate("问题", "回答", List.of()).status());
    }

    @Test
    @DisplayName("非 JSON / 非数组 / 调用异常一律判为失败（不落库，可重试）")
    void failsOnMalformedOutput() {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("这里没有问题");
        assertEquals(RecommendedQuestionsPayload.Status.FAILED,
                generator.generate("问题", "回答", List.of()).status());

        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("{\"questions\":[\"a\"]}");
        assertEquals(RecommendedQuestionsPayload.Status.FAILED,
                generator.generate("问题", "回答", List.of()).status());

        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenThrow(new IllegalStateException("模型不可用"));
        assertEquals(RecommendedQuestionsPayload.Status.FAILED,
                generator.generate("问题", "回答", List.of()).status());
    }

    @Test
    @DisplayName("grounding 片段按「序号 + 文档名 + 文本」注入提示词")
    void injectsGroundingChunks() {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("[\"问题一\"]");

        generator.generate("转专业条件", "回答内容", List.of(
                GroundingChunk.builder().docName("转专业管理办法.pdf").text("申请条件与学分要求").build()));

        ArgumentCaptor<Map<String, String>> slots = ArgumentCaptor.forClass(Map.class);
        verify(promptTemplateLoader).render(anyString(), slots.capture());
        String chunks = slots.getValue().get("chunks");
        assertTrue(chunks.contains("转专业管理办法.pdf"), chunks);
        assertTrue(chunks.contains("申请条件与学分要求"), chunks);
    }

    @Test
    @DisplayName("无 grounding 时降级为「仅依据问答生成」")
    void degradesWithoutGrounding() {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("[\"问题一\"]");

        generator.generate("问题", "回答", List.of());

        ArgumentCaptor<Map<String, String>> slots = ArgumentCaptor.forClass(Map.class);
        verify(promptTemplateLoader).render(anyString(), slots.capture());
        assertTrue(slots.getValue().get("chunks").contains("无检索片段"), slots.getValue().get("chunks"));
    }
}

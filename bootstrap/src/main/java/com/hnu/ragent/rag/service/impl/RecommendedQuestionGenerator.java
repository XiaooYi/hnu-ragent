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

package com.hnu.ragent.rag.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.hnu.ragent.framework.convention.ChatMessage;
import com.hnu.ragent.framework.convention.ChatRequest;
import com.hnu.ragent.framework.convention.GroundingChunk;
import com.hnu.ragent.framework.trace.RagTraceNode;
import com.hnu.ragent.infra.chat.LLMService;
import com.hnu.ragent.infra.enums.Tier;
import com.hnu.ragent.rag.core.prompt.PromptTemplateLoader;
import com.hnu.ragent.rag.dto.RecommendedQuestionsPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.hnu.ragent.rag.constant.RAGConstant.RECOMMENDED_QUESTIONS_PROMPT_PATH;

/**
 * 推荐追问问题生成器
 * <p>
 * 答案完成后的派生调用（FAST 档），由接口按需触发，不在 chat 流式关键路径内。
 * 拆成独立 Bean 是为了让 {@link RagTraceNode} 的 AOP 生效（同类 self-call 不走代理）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendedQuestionGenerator {

    private static final int DEFAULT_RECOMMEND_COUNT = 3;
    private static final int MAX_QUESTION_CHARS = 1000;
    private static final int MAX_ANSWER_CHARS = 6000;
    private static final int MAX_CHUNKS_CHARS = 6000;
    private static final int MAX_OUTPUT_TOKENS = 256;
    private static final int MAX_QUESTION_ITEM_CHARS = 200;

    private final PromptTemplateLoader promptTemplateLoader;
    private final LLMService llmService;

    @RagTraceNode(name = "recommended-question-gen", type = "RECOMMEND_GEN")
    public RecommendedQuestionsPayload generate(String question, String answer, List<GroundingChunk> chunks) {
        try {
            int count = DEFAULT_RECOMMEND_COUNT;
            // 在真实模型调用边界统一限制输入长度，避免持久化数据异常导致提示词无界增长
            String prompt = promptTemplateLoader.render(
                    RECOMMENDED_QUESTIONS_PROMPT_PATH,
                    Map.of(
                            "chunks", buildChunksText(chunks),
                            "question", truncate(question, MAX_QUESTION_CHARS),
                            "answer", truncate(answer, MAX_ANSWER_CHARS)
                    )
            );

            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(ChatMessage.user(prompt)))
                    .temperature(0.7D)
                    .topP(0.8D)
                    .maxTokens(MAX_OUTPUT_TOKENS)
                    .thinking(false)
                    .build();
            return parseQuestions(llmService.chat(request, Tier.FAST), count);
        } catch (Exception ex) {
            log.warn("生成推荐追问问题失败", ex);
            return RecommendedQuestionsPayload.failed();
        }
    }

    /**
     * 拼装 grounding 片段文本；无片段时降级为「仅依据问答生成」
     */
    private String buildChunksText(List<GroundingChunk> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return "（无检索片段，仅依据问答生成）";
        }
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (GroundingChunk chunk : chunks) {
            if (chunk == null || StrUtil.isBlank(chunk.getText()) || sb.length() >= MAX_CHUNKS_CHARS) {
                continue;
            }
            String prefix = idx++ + ". 【" + StrUtil.nullToEmpty(chunk.getDocName()) + "】";
            int remaining = MAX_CHUNKS_CHARS - sb.length() - prefix.length() - 1;
            if (remaining <= 0) {
                break;
            }
            sb.append(prefix).append(truncate(chunk.getText(), remaining)).append('\n');
        }
        return sb.isEmpty() ? "（无检索片段，仅依据问答生成）" : sb.toString().stripTrailing();
    }

    /**
     * 健壮解析：去代码围栏 → JSON 数组 → trim/去空/去重/截断；非数组或异常一律视为失败
     */
    private RecommendedQuestionsPayload parseQuestions(String raw, int count) {
        if (StrUtil.isBlank(raw)) {
            return RecommendedQuestionsPayload.failed();
        }
        String stripped = stripCodeFence(raw).trim();
        if (StrUtil.isBlank(stripped)) {
            return RecommendedQuestionsPayload.failed();
        }
        try {
            JSONArray array = JSONUtil.parseArray(stripped);
            LinkedHashSet<String> dedup = new LinkedHashSet<>();
            for (Object item : array) {
                if (!(item instanceof CharSequence)) {
                    continue;
                }
                String text = truncate(StrUtil.trim(item.toString()), MAX_QUESTION_ITEM_CHARS);
                if (StrUtil.isNotBlank(text)) {
                    dedup.add(text);
                }
            }
            if (dedup.isEmpty()) {
                return RecommendedQuestionsPayload.empty();
            }
            List<String> result = new ArrayList<>(dedup);
            return RecommendedQuestionsPayload.success(
                    result.size() > count ? result.subList(0, count) : result);
        } catch (Exception ex) {
            log.warn("解析推荐追问问题失败，原文：{}", StrUtil.maxLength(raw, 200));
            return RecommendedQuestionsPayload.failed();
        }
    }

    private String truncate(String value, int maxChars) {
        String text = StrUtil.nullToEmpty(value);
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    /**
     * 去除可能的 markdown 代码围栏（```json ... ``` 或 ``` ... ```）
     */
    private String stripCodeFence(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline > 0) {
                text = text.substring(firstNewline + 1);
            }
            int lastFence = text.lastIndexOf("```");
            if (lastFence >= 0) {
                text = text.substring(0, lastFence);
            }
        }
        return text;
    }
}

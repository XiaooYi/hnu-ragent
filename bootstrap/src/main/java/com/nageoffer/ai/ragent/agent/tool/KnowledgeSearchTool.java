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

package com.nageoffer.ai.ragent.agent.tool;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索工具
 * <p>
 * Agent 的默认检索入口：模型自带查询词，按需多轮检索（RAG 主链路是「检索一次就回答」，
 * Agent 可以换关键词再查）。输出为带序号的证据片段，便于模型引用与后续收口
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeSearchTool implements AgentTool {

    private static final int DEFAULT_TOP_K = 5;

    private static final int MAX_TOP_K = 20;

    private static final int MAX_CHUNK_CHARS = 1200;

    private final RetrieverService retrieverService;

    @Override
    public String id() {
        return "knowledge_search";
    }

    @Override
    public String name() {
        return "知识库检索";
    }

    @Override
    public String description() {
        return "在校内知识库中检索资料。适合查询制度、培养方案、通知等本地资料；"
                + "第一次没查到时要换更短或更具体的关键词再试，不要直接放弃";
    }

    @Override
    public List<AgentToolParameter> parameters() {
        return List.of(
                AgentToolParameter.required("query", "string", "检索关键词或问题"),
                AgentToolParameter.optional("topK", "integer", "返回片段数，默认 5，最大 20")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String query = stringArg(arguments, "query");
        if (StrUtil.isBlank(query)) {
            return "缺少参数 query：请给出检索关键词";
        }
        int topK = resolveTopK(arguments);
        List<RetrievedChunk> chunks = retrieverService.retrieve(query.trim(), topK);
        if (CollUtil.isEmpty(chunks)) {
            return "未检索到相关资料（query=" + query + "）。可以换更短或更具体的关键词再试一次";
        }
        StringBuilder text = new StringBuilder("检索到 ").append(chunks.size()).append(" 条资料：\n");
        int index = 1;
        for (RetrievedChunk chunk : chunks) {
            String content = trim(chunk.getText());
            if (StrUtil.isBlank(content)) {
                continue;
            }
            text.append(index++).append(". ");
            if (StrUtil.isNotBlank(chunk.getDocName())) {
                text.append("【").append(chunk.getDocName()).append("】");
            }
            text.append(content).append('\n');
        }
        return text.toString().trim();
    }

    private int resolveTopK(Map<String, Object> arguments) {
        Object value = arguments == null ? null : arguments.get("topK");
        int topK = value instanceof Number number ? number.intValue() : DEFAULT_TOP_K;
        if (topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }

    private String trim(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        return trimmed.length() > MAX_CHUNK_CHARS ? trimmed.substring(0, MAX_CHUNK_CHARS) + "…" : trimmed;
    }

    private String stringArg(Map<String, Object> arguments, String key) {
        Object value = arguments == null ? null : arguments.get(key);
        return value == null ? null : String.valueOf(value);
    }
}

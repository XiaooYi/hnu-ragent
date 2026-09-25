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

package com.nageoffer.ai.ragent.rag.core.prompt;

import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行内引用规则的动态追加规则
 */
class RAGPromptServiceTest {

    @Test
    @DisplayName("开关开启且存在知识库上下文时追加行内引用规则")
    void appendsCitationRulesWhenEnabled() {
        String systemPrompt = service(true).buildSystemPrompt(kbContext());

        assertTrue(systemPrompt.contains("[N](#cite-N)"), systemPrompt);
        assertTrue(systemPrompt.contains("引用编号"), systemPrompt);
    }

    @Test
    @DisplayName("开关关闭时不追加引用规则，节省首字延迟")
    void skipsCitationRulesWhenDisabled() {
        String systemPrompt = service(false).buildSystemPrompt(kbContext());

        assertFalse(systemPrompt.contains("[N](#cite-N)"), systemPrompt);
    }

    @Test
    @DisplayName("没有知识库上下文时不追加引用规则（无资料可引）")
    void skipsCitationRulesWithoutKbContext() {
        PromptContext context = PromptContext.builder()
                .question("你好")
                .mcpContext("<tool-data>动态数据</tool-data>")
                .mcpIntents(List.of())
                .kbIntents(List.of())
                .intentChunks(Map.of())
                .build();

        String systemPrompt = service(true).buildSystemPrompt(context);

        assertFalse(systemPrompt.contains("[N](#cite-N)"), systemPrompt);
    }

    private RAGPromptService service(boolean citationEnabled) {
        RAGConfigProperties properties = new RAGConfigProperties();
        properties.setCitationEnabled(citationEnabled);
        return new RAGPromptService(new PromptTemplateLoader(new DefaultResourceLoader()), properties);
    }

    private PromptContext kbContext() {
        return PromptContext.builder()
                .question("转专业需要什么条件")
                .kbContext("<documents><content ref=\"1\">资料</content></documents>")
                .mcpIntents(List.of())
                .kbIntents(List.of())
                .intentChunks(Map.of())
                .build();
    }
}

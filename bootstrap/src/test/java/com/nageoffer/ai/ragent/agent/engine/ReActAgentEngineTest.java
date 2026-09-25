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

package com.nageoffer.ai.ragent.agent.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.dto.AgentRequest;
import com.nageoffer.ai.ragent.agent.dto.AgentRunResult;
import com.nageoffer.ai.ragent.agent.tool.AgentTool;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog;
import com.nageoffer.ai.ragent.agent.tool.AgentToolParameter;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ReAct 引擎：工具调用 → 观察 → 收口，以及协议容错 / 步数上限 / 工具异常 / 取消
 */
class ReActAgentEngineTest {

    private final AgentProperties properties = new AgentProperties();
    private final PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
    private final LLMService llmService = mock(LLMService.class);
    private final Deque<String> modelOutputs = new ArrayDeque<>();
    private final Deque<AgentTool> tools = new ArrayDeque<>();

    private ReActAgentEngine engine;

    @BeforeEach
    void setUp() {
        when(promptTemplateLoader.render(anyString(), anyMap())).thenReturn("提示词");
        when(llmService.chat(any(ChatRequest.class), eq(Tier.STANDARD)))
                .thenAnswer(invocation -> modelOutputs.isEmpty() ? "{\"final\":\"兜底\"}" : modelOutputs.poll());
        properties.setMaxSteps(3);
        engine = new ReActAgentEngine(properties, catalog(), llmService, promptTemplateLoader, new ObjectMapper());
    }

    @Test
    @DisplayName("先调工具再收口：步骤留痕，回答取模型 final")
    void runsToolThenFinalAnswer() {
        modelOutputs.add("{\"thought\":\"需要查资料\",\"action\":{\"tool\":\"knowledge_search\",\"arguments\":{\"query\":\"转专业\"}}}");
        modelOutputs.add("{\"thought\":\"信息足够\",\"final\":\"转专业需要提交申请表\"}");
        tools.add(tool("知识库命中：转专业条件"));

        AgentRunResult result = engine.run(AgentRequest.builder().question("转专业需要什么材料").build());

        assertEquals("转专业需要提交申请表", result.answer());
        assertEquals(AgentRunResult.StopReason.FINAL_ANSWER, result.stopReason());
        assertEquals(2, result.steps().size());
        assertEquals("knowledge_search", result.steps().get(0).toolId());
        assertEquals(Map.of("query", "转专业"), result.steps().get(0).arguments());
        assertTrue(result.steps().get(0).observation().contains("转专业条件"));
        assertNull(result.steps().get(1).observation());
    }

    @Test
    @DisplayName("模型一直只调工具时按步数上限收口，不返回空")
    void stopsAtMaxSteps() {
        modelOutputs.add("{\"action\":{\"tool\":\"knowledge_search\",\"arguments\":{\"query\":\"a\"}}}");
        modelOutputs.add("{\"action\":{\"tool\":\"knowledge_search\",\"arguments\":{\"query\":\"b\"}}}");
        modelOutputs.add("{\"action\":{\"tool\":\"knowledge_search\",\"arguments\":{\"query\":\"c\"}}}");
        tools.add(tool("第一次观察"));
        tools.add(tool("第二次观察"));
        tools.add(tool("第三次观察"));

        AgentRunResult result = engine.run(AgentRequest.builder().question("问题").build());

        assertEquals(AgentRunResult.StopReason.MAX_STEPS, result.stopReason());
        assertTrue(result.answer().contains("第三次观察"));
        assertEquals(3, result.steps().size());
    }

    @Test
    @DisplayName("输出不是 JSON 时整段作为回答，不因格式问题失败")
    void fallsBackToPlainText() {
        modelOutputs.add("这个问题我直接回答：需要申请表和成绩单。");

        AgentRunResult result = engine.run(AgentRequest.builder().question("问题").build());

        assertEquals(AgentRunResult.StopReason.FALLBACK_TEXT, result.stopReason());
        assertEquals("这个问题我直接回答：需要申请表和成绩单。", result.answer());
    }

    @Test
    @DisplayName("工具抛异常时回灌错误观察，循环继续直到收口")
    void keepsGoingAfterToolFailure() {
        modelOutputs.add("{\"action\":{\"tool\":\"boom\",\"arguments\":{}}}");
        modelOutputs.add("{\"final\":\"最终回答\"}");
        tools.add(failingTool());

        AgentRunResult result = engine.run(AgentRequest.builder().question("问题").build());

        assertEquals("最终回答", result.answer());
        assertEquals(2, result.steps().size());
        assertTrue(result.steps().get(0).observation().contains("工具执行失败"));
        assertTrue(result.steps().get(0).observation().contains("上游 503"));
    }

    private AgentTool failingTool() {
        return new AgentTool() {
            @Override
            public String id() {
                return "boom";
            }

            @Override
            public String name() {
                return "会失败的工具";
            }

            @Override
            public String description() {
                return "总是抛异常";
            }

            @Override
            public List<AgentToolParameter> parameters() {
                return List.of();
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                throw new IllegalStateException("上游 503");
            }
        };
    }

    @Test
    @DisplayName("线程被中断时抛 CancellationException，不记成模型故障")
    void respectsCancellation() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> engine.run(AgentRequest.builder().question("问题").build()));
        } finally {
            Thread.interrupted();
        }
    }

    private AgentToolCatalog catalog() {
        return new AgentToolCatalog(List.of(), null, properties) {
            @Override
            public String execute(String toolId, Map<String, Object> arguments) {
                AgentTool tool = tools.poll();
                return tool == null ? "无工具可用" : tool.execute(arguments);
            }

            @Override
            public String describeForPrompt() {
                return "工具目录";
            }
        };
    }

    private AgentTool tool(String output) {
        return new AgentTool() {
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
                return "检索";
            }

            @Override
            public List<AgentToolParameter> parameters() {
                return List.of();
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                return output;
            }
        };
    }
}

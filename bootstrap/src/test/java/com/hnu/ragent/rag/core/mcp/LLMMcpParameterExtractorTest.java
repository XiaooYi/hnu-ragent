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

package com.hnu.ragent.rag.core.mcp;

import com.hnu.ragent.framework.convention.ChatRequest;
import com.hnu.ragent.infra.chat.LLMService;
import com.hnu.ragent.rag.core.prompt.PromptTemplateLoader;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MCP 提参三态：可调用 / 需澄清 / 提取失败
 */
class LLMMcpParameterExtractorTest {

    private final LLMService llmService = mock(LLMService.class);
    private final PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);

    private LLMMcpParameterExtractor extractor;

    @BeforeEach
    void setUp() {
        when(promptTemplateLoader.load(anyString())).thenReturn("系统提示词");
        when(promptTemplateLoader.render(anyString(), anyMap())).thenReturn("用户提示词");
        extractor = new LLMMcpParameterExtractor(llmService, promptTemplateLoader);
    }

    @Test
    @DisplayName("无参工具直接成功且不调用 LLM")
    void succeedsWithoutParameters() {
        Tool tool = tool("noop_tool", Map.of(), List.of());

        McpExtractionResult result = extractor.extractParameters("你好", tool);

        assertEquals(McpExtractionResult.Status.SUCCESS, result.status());
        assertTrue(result.params().isEmpty());
    }

    @Test
    @DisplayName("必填参数齐备时成功，并补齐 schema 默认值")
    void succeedsAndFillsDefaults() {
        when(llmService.chat(any(ChatRequest.class))).thenReturn("{\"dept\":\"计算机学院\",\"topN\":10}");

        McpExtractionResult result = extractor.extractParameters("计算机学院有哪些奖学金", leaveTool());

        assertEquals(McpExtractionResult.Status.SUCCESS, result.status());
        assertEquals("计算机学院", result.params().get("dept"));
        assertEquals(10, result.params().get("topN"));
        assertEquals("本学年", result.params().get("term"), "仅 SUCCESS 才用 schema 默认值补齐");
    }

    @Test
    @DisplayName("必填且无默认值的参数缺失时判为需澄清，并列出缺失项")
    void needsClarificationWhenRequiredMissing() {
        // dept 为必填无默认值，模型没给 → 需要向用户追问，不能拿残缺参数去调工具
        when(llmService.chat(any(ChatRequest.class))).thenReturn("{\"topN\":10}");

        McpExtractionResult result = extractor.extractParameters("有哪些奖学金", leaveTool());

        assertEquals(McpExtractionResult.Status.NEED_CLARIFICATION, result.status());
        assertEquals(List.of("dept"), result.missingRequired());
        assertEquals(10, result.params().get("topN"), "已抽取到的其它参数仍保留");
    }

    @Test
    @DisplayName("必填参数被显式写成 null 同样判为需澄清")
    void needsClarificationWhenRequiredIsNull() {
        when(llmService.chat(any(ChatRequest.class))).thenReturn("{\"dept\":null}");

        McpExtractionResult result = extractor.extractParameters("有哪些奖学金", leaveTool());

        assertEquals(McpExtractionResult.Status.NEED_CLARIFICATION, result.status());
        assertEquals(List.of("dept"), result.missingRequired());
    }

    @Test
    @DisplayName("响应畸形（非 JSON / 空响应）判为提取失败")
    void failsOnMalformedResponse() {
        when(llmService.chat(any(ChatRequest.class))).thenReturn("我不知道该怎么提取");
        assertEquals(McpExtractionResult.Status.FAILED,
                extractor.extractParameters("有哪些奖学金", leaveTool()).status());

        when(llmService.chat(any(ChatRequest.class))).thenReturn("   ");
        assertEquals(McpExtractionResult.Status.FAILED,
                extractor.extractParameters("有哪些奖学金", leaveTool()).status());
    }

    @Test
    @DisplayName("值类型或枚举非法判为提取失败，且非法值不进入参数")
    void failsOnInvalidValue() {
        when(llmService.chat(any(ChatRequest.class))).thenReturn("{\"dept\":\"计算机学院\",\"topN\":\"十条\"}");

        McpExtractionResult result = extractor.extractParameters("计算机学院奖学金", leaveTool());

        assertEquals(McpExtractionResult.Status.FAILED, result.status());
        assertTrue(result.params().isEmpty(), "失败态不产出可用于调用的参数");
    }

    @Test
    @DisplayName("枚举值未命中时判为提取失败（避免过滤条件被静默移除）")
    void failsOnEnumMismatch() {
        when(llmService.chat(any(ChatRequest.class))).thenReturn("{\"dept\":\"计算机学院\",\"term\":\"下学期\"}");

        assertEquals(McpExtractionResult.Status.FAILED,
                extractor.extractParameters("计算机学院奖学金", leaveTool()).status());
    }

    @Test
    @DisplayName("字符串参数接受标量（学号、编号常被模型写成数字）")
    void acceptsScalarForStringParameter() {
        when(promptTemplateLoader.render(anyString(), anyMap())).thenReturn("用户提示词");
        when(llmService.chat(any(ChatRequest.class))).thenReturn("{\"studentNo\":20230001}");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("studentNo", Map.of("type", "string", "description", "学号"));
        Tool tool = tool("query_student", properties, List.of("studentNo"));

        McpExtractionResult result = extractor.extractParameters("查学号 20230001", tool);

        assertEquals(McpExtractionResult.Status.SUCCESS, result.status());
        assertEquals("20230001", result.params().get("studentNo"));
    }

    @Test
    @DisplayName("LLM 调用异常判为提取失败")
    void failsWhenLlmThrows() {
        when(llmService.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("模型不可用"));

        assertEquals(McpExtractionResult.Status.FAILED,
                extractor.extractParameters("有哪些奖学金", leaveTool()).status());
    }

    /**
     * 校园奖学金查询工具：dept 必填无默认值，topN 可选无默认值，term 可选有默认值
     */
    private Tool leaveTool() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("dept", Map.of("type", "string", "description", "院系名称"));
        properties.put("topN", Map.of("type", "integer", "description", "返回条数"));
        properties.put("term", Map.of("type", "string", "description", "学年", "default", "本学年",
                "enum", List.of("本学年", "上一学年")));
        return tool("query_scholarship", properties, List.of("dept"));
    }

    private Tool tool(String name, Map<String, Object> properties, List<String> required) {
        JsonSchema schema = new JsonSchema("object", properties, required, null, null, null);
        return new Tool(name, "测试工具", "测试工具描述", schema, null, null, null);
    }
}

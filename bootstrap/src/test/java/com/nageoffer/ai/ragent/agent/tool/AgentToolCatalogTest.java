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

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具目录：来源合并、白名单、提示词渲染与执行
 */
class AgentToolCatalogTest {

    private final AgentProperties properties = new AgentProperties();

    @Test
    @DisplayName("本地工具与 MCP 工具进入同一目录，MCP 工具按非只读处理")
    void mergesLocalAndMcpTools() {
        AgentToolCatalog catalog = catalog(localTool(), mcpRegistry(Map.of("youcom_search", "联网搜索")));

        List<AgentToolCatalog.AgentToolDescriptor> tools = catalog.listTools();

        assertEquals(2, tools.size());
        AgentToolCatalog.AgentToolDescriptor local = tools.get(0);
        assertEquals("knowledge_search", local.id());
        assertTrue(local.readOnly());
        assertEquals(AgentToolCatalog.AgentToolDescriptor.Source.LOCAL, local.source());

        AgentToolCatalog.AgentToolDescriptor mcp = tools.get(1);
        assertEquals("youcom_search", mcp.id());
        assertFalse(mcp.readOnly(), "MCP 工具读写属性无法判定，按非只读保守处理");
        assertEquals(AgentToolCatalog.AgentToolDescriptor.Source.MCP, mcp.source());
    }

    @Test
    @DisplayName("白名单收窄目录，未注册的项被忽略")
    void appliesWhitelist() {
        properties.setAllowedTools(List.of("youcom_search", "  ", "not-registered"));
        AgentToolCatalog catalog = catalog(localTool(), mcpRegistry(Map.of("youcom_search", "联网搜索")));

        List<String> ids = catalog.listTools().stream().map(AgentToolCatalog.AgentToolDescriptor::id).toList();

        assertEquals(List.of("youcom_search"), ids);
        assertFalse(catalog.describeForPrompt().contains("knowledge_search"));
    }

    @Test
    @DisplayName("提示词渲染包含工具名、用途、参数与必填标记")
    void rendersPromptDescription() {
        AgentToolCatalog catalog = catalog(localTool(), null);

        String prompt = catalog.describeForPrompt();

        assertTrue(prompt.contains("knowledge_search"));
        assertTrue(prompt.contains("在校内知识库中检索"));
        assertTrue(prompt.contains("query（string，必填）"));
        assertTrue(prompt.contains("topK（integer，可选）"));
    }

    @Test
    @DisplayName("目录为空时给出可读提示；执行未知工具返回可用清单")
    void handlesEmptyCatalogAndUnknownTool() {
        properties.setAllowedTools(List.of("none"));
        AgentToolCatalog catalog = catalog(localTool(), null);

        assertTrue(catalog.describeForPrompt().contains("没有可用工具"));
        assertTrue(catalog.execute("knowledge_search", Map.of()).contains("工具不存在或未启用"));
    }

    @Test
    @DisplayName("写操作确认策略：非只读工具需要确认，登记为只读的 MCP 工具不需要")
    void decidesConfirmationPolicy() {
        AgentToolCatalog catalog = catalog(localTool(), mcpRegistry(Map.of("order_create", "下单")));

        assertFalse(catalog.requiresConfirmation("knowledge_search"));
        assertTrue(catalog.requiresConfirmation("order_create"), "MCP 工具默认按写操作处理");
        assertFalse(catalog.requiresConfirmation("unknown_tool"), "目录外的工具不需要确认，直接拒绝执行");

        properties.setReadOnlyTools(List.of("order_create"));
        assertFalse(catalog.requiresConfirmation("order_create"), "确认过确实只读后不再每次确认");

        properties.setReadOnlyTools(List.of());
        properties.getConfirm().setRequired(false);
        assertFalse(catalog.requiresConfirmation("order_create"), "关掉确认开关即允许直接执行");
    }

    @Test
    @DisplayName("确认卡参数说明取本地工具的参数描述，未知参数回退原值")
    void describesConfirmFields() {
        AgentToolCatalog catalog = catalog(localTool(), null);

        Map<String, String> fields = catalog.describeArguments("knowledge_search",
                Map.of("query", "转专业", "unknown", "x"));

        assertEquals("检索关键词", fields.get("query"));
        assertEquals("x", fields.get("unknown"));
        assertTrue(catalog.describeArguments("knowledge_search", null).isEmpty());
    }

    @Test
    @DisplayName("执行本地工具与 MCP 工具，MCP 错误结果带错误前缀")
    void executesBothSources() {
        McpToolExecutor failing = mock(McpToolExecutor.class);
        when(failing.getToolDefinition()).thenReturn(tool("youcom_search", "联网搜索"));
        when(failing.execute(org.mockito.ArgumentMatchers.anyMap())).thenReturn(
                CallToolResult.builder().content(List.of(new TextContent("未配置 YDC_API_KEY"))).isError(true).build());
        McpToolRegistry registry = mock(McpToolRegistry.class);
        when(registry.listAllTools()).thenReturn(List.of(tool("youcom_search", "联网搜索")));
        when(registry.getExecutor("youcom_search")).thenReturn(Optional.of(failing));

        AgentToolCatalog catalog = catalog(localTool(), registry);

        assertTrue(catalog.execute("knowledge_search", Map.of("query", "转专业")).contains("命中"));
        assertTrue(catalog.execute("youcom_search", Map.of("query", "x")).contains("工具返回错误"));
    }

    private AgentToolCatalog catalog(AgentTool localTool, McpToolRegistry registry) {
        return new AgentToolCatalog(localTool == null ? List.of() : List.of(localTool), registry, properties);
    }

    private AgentTool localTool() {
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
                return "在校内知识库中检索资料";
            }

            @Override
            public List<AgentToolParameter> parameters() {
                return List.of(
                        AgentToolParameter.required("query", "string", "检索关键词"),
                        AgentToolParameter.optional("topK", "integer", "返回片段数"));
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                return "命中 1 条：" + arguments.get("query");
            }
        };
    }

    private McpToolRegistry mcpRegistry(Map<String, String> tools) {
        McpToolRegistry registry = mock(McpToolRegistry.class);
        when(registry.listAllTools()).thenReturn(tools.entrySet().stream()
                .map(entry -> tool(entry.getKey(), entry.getValue()))
                .toList());
        when(registry.getExecutor(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        return registry;
    }

    private Tool tool(String name, String description) {
        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), false, null, null))
                .build();
    }
}

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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 工具目录
 * <p>
 * 把本地工具（{@link AgentTool} bean）与 MCP 工具（{@link McpToolRegistry}）合成同一份目录：
 * 提示词用 {@link #describeForPrompt()} 渲染给模型看，运行时用 {@link #execute(String, Map)} 执行。
 * 白名单（{@code ai.agent.allowed-tools}）在此处统一收口，运行期不再各处判断
 * <p>
 * 写操作边界：本地工具由 {@link AgentTool#readOnly()} 声明；MCP 工具的读写属性无法从协议判定，
 * 一律按**非只读**处理（保守），等 P3 的确认流程就绪后才允许它们进入目录
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentToolCatalog {

    private final List<AgentTool> localTools;
    private final McpToolRegistry mcpToolRegistry;
    private final AgentProperties agentProperties;

    /**
     * 当前可用工具（已按白名单过滤）
     */
    public List<AgentToolDescriptor> listTools() {
        List<AgentToolDescriptor> descriptors = new ArrayList<>();
        for (AgentTool tool : localTools) {
            descriptors.add(new AgentToolDescriptor(tool.id(), tool.name(), tool.description(),
                    tool.parameters(), tool.readOnly() || declaredReadOnly(tool.id()),
                    AgentToolDescriptor.Source.LOCAL));
        }
        if (mcpToolRegistry != null) {
            for (Tool tool : mcpToolRegistry.listAllTools()) {
                descriptors.add(new AgentToolDescriptor(tool.name(), tool.name(), tool.description(),
                        List.of(), declaredReadOnly(tool.name()), AgentToolDescriptor.Source.MCP));
            }
        }
        return applyWhitelist(descriptors);
    }

    /**
     * 写操作是否需要先经用户确认：非只读且开了确认开关
     */
    public boolean requiresConfirmation(String toolId) {
        if (!Boolean.TRUE.equals(agentProperties.getConfirm().getRequired())) {
            return false;
        }
        return listTools().stream()
                .filter(tool -> tool.id().equals(toolId))
                .findFirst()
                .map(tool -> !tool.readOnly())
                .orElse(false);
    }

    /**
     * 确认卡字段：模型给出的入参 + 人工可读的字段标题
     */
    public Map<String, String> describeArguments(String toolId, Map<String, Object> arguments) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (arguments == null) {
            return fields;
        }
        Map<String, AgentToolParameter> parameters = listTools().stream()
                .filter(tool -> tool.id().equals(toolId))
                .findFirst()
                .map(AgentToolDescriptor::parameterMap)
                .orElse(Map.of());
        arguments.forEach((key, value) -> {
            AgentToolParameter parameter = parameters.get(key);
            fields.put(key, parameter == null ? String.valueOf(value) : parameter.description());
        });
        return fields;
    }

    /**
     * 渲染给模型的工具清单；目录为空时返回可读提示
     */
    public String describeForPrompt() {
        List<AgentToolDescriptor> tools = listTools();
        if (tools.isEmpty()) {
            return "当前没有可用工具，只能依据自身知识回答，并说明无法查证。";
        }
        StringBuilder text = new StringBuilder();
        for (AgentToolDescriptor tool : tools) {
            text.append("- ").append(tool.id()).append("：").append(tool.description());
            if (!tool.readOnly()) {
                text.append("（写操作）");
            }
            text.append('\n');
            if (tool.parameters().isEmpty()) {
                text.append("    参数：无\n");
            } else {
                for (AgentToolParameter parameter : tool.parameters()) {
                    text.append("    ").append(parameter.describe()).append('\n');
                }
            }
        }
        return text.toString().trim();
    }

    /**
     * 执行工具；工具不存在或未在白名单内时返回错误提示（不抛异常，交给模型决策）
     */
    public String execute(String toolId, Map<String, Object> arguments) {
        Optional<AgentToolDescriptor> descriptor = listTools().stream()
                .filter(tool -> tool.id().equals(toolId))
                .findFirst();
        if (descriptor.isEmpty()) {
            return "工具不存在或未启用：" + toolId + "。可用工具：" + availableIds();
        }
        if (descriptor.get().source() == AgentToolDescriptor.Source.MCP) {
            return executeMcp(toolId, arguments);
        }
        Optional<AgentTool> tool = localTools.stream()
                .filter(each -> each.id().equals(toolId))
                .findFirst();
        if (tool.isEmpty()) {
            return "工具不存在或未启用：" + toolId + "。可用工具：" + availableIds();
        }
        return tool.get().execute(arguments == null ? Map.of() : arguments);
    }

    private String executeMcp(String toolId, Map<String, Object> arguments) {
        if (mcpToolRegistry == null) {
            return "MCP 工具不可用：" + toolId;
        }
        return mcpToolRegistry.getExecutor(toolId)
                .map(executor -> {
                    CallToolResult result = executor.execute(arguments == null ? Map.of() : arguments);
                    return renderResult(result);
                })
                .orElse("MCP 工具未注册：" + toolId);
    }

    private String renderResult(CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return "工具没有返回内容";
        }
        StringBuilder text = new StringBuilder();
        result.content().forEach(content -> {
            if (content instanceof TextContent textContent) {
                text.append(textContent.text()).append('\n');
            } else {
                text.append(content.toString()).append('\n');
            }
        });
        String rendered = text.toString().trim();
        if (result.isError()) {
            return "工具返回错误：" + rendered;
        }
        return rendered;
    }

    private List<AgentToolDescriptor> applyWhitelist(List<AgentToolDescriptor> descriptors) {
        List<String> allowed = agentProperties.getAllowedTools();
        if (allowed == null || allowed.isEmpty()) {
            return descriptors;
        }
        List<String> normalized = allowed.stream()
                .filter(StrUtil::isNotBlank)
                .map(StrUtil::trim)
                .toList();
        if (normalized.isEmpty()) {
            return descriptors;
        }
        List<AgentToolDescriptor> filtered = descriptors.stream()
                .filter(tool -> normalized.contains(tool.id()))
                .toList();
        List<String> unknown = normalized.stream()
                .filter(id -> filtered.stream().noneMatch(tool -> tool.id().equals(id)))
                .toList();
        if (!unknown.isEmpty()) {
            log.warn("Agent 工具白名单中有未注册的工具，已忽略: {}", unknown);
        }
        return filtered;
    }

    private String availableIds() {
        List<AgentToolDescriptor> tools = listTools();
        if (tools.isEmpty()) {
            return "无";
        }
        return String.join(", ", tools.stream().map(AgentToolDescriptor::id).toList());
    }

    private boolean declaredReadOnly(String toolId) {
        List<String> declared = agentProperties.getReadOnlyTools();
        return declared != null && declared.contains(toolId);
    }

    /**
     * 工具描述（提示词渲染与运行期查找共用的唯一结构）
     */
    public record AgentToolDescriptor(String id,
                                      String name,
                                      String description,
                                      List<AgentToolParameter> parameters,
                                      boolean readOnly,
                                      Source source) {

        public enum Source {
            /** 本进程内的工具 */
            LOCAL,
            /** 通过 MCP 协议调用的工具 */
            MCP
        }

        public AgentToolDescriptor {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }

        /**
         * 参数名 → 描述，便于按名取说明（确认卡与错误提示用）
         */
        public Map<String, AgentToolParameter> parameterMap() {
            Map<String, AgentToolParameter> map = new LinkedHashMap<>();
            parameters.forEach(parameter -> map.put(parameter.name(), parameter));
            return map;
        }
    }
}

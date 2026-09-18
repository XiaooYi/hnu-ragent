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

package com.nageoffer.ai.ragent.rag.core.mcp;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MCP_PARAMETER_EXTRACT_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MCP_PARAMETER_EXTRACT_USER_PROMPT_PATH;

/**
 * 基于 LLM 的 MCP 参数提取器实现。
 *
 * <p>核心流程：
 * <ol>
 *     <li>校验工具定义和 inputSchema，缺少可提取参数时直接返回空参数。</li>
 *     <li>将 MCP Tool 的 schema 转为面向 LLM 的工具描述，并与用户问题一起渲染参数提取 Prompt。</li>
 *     <li>以低温度配置调用 LLM，要求模型只根据工具定义和用户问题输出 JSON 参数对象。</li>
 *     <li>清洗 LLM 返回中可能存在的 Markdown 代码块，解析 JSON 对象并过滤掉工具未声明的字段。</li>
 *     <li>将 JSON 值转换为普通 Java 类型，再按 schema default 补齐缺省参数。</li>
 *     <li>解析或调用异常时降级为仅包含默认值的参数 Map，保持 MCP Tool 调用链继续执行。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMMcpParameterExtractor implements McpParameterExtractor {

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final Gson gson = new Gson();

    @Override
    public Map<String, Object> extractParameters(String userQuestion, Tool tool) {
        return extractParameters(userQuestion, tool, null);
    }

    @Override
    public Map<String, Object> extractParameters(String userQuestion, Tool tool, String customPromptTemplate) {
        // 无工具定义或无参数 schema 时，没有可供 LLM 匹配的参数空间，直接返回空参数。
        if (tool == null || tool.inputSchema() == null || CollUtil.isEmpty(tool.inputSchema().properties())) {
            return Collections.emptyMap();
        }

        // 系统 Prompt 支持按意图节点覆盖；未配置时使用通用 MCP 参数提取模板。
        List<ChatMessage> messages = new ArrayList<>(2);
        String systemPrompt = StrUtil.isNotBlank(customPromptTemplate)
                ? customPromptTemplate
                : promptTemplateLoader.load(MCP_PARAMETER_EXTRACT_PROMPT_PATH);

        messages.add(ChatMessage.system(systemPrompt));
        // 用户 Prompt 承载结构化工具定义和原始问题，LLM 只从这两部分推断参数值。
        String userPrompt = promptTemplateLoader.render(MCP_PARAMETER_EXTRACT_USER_PROMPT_PATH, Map.of(
                "tool_definition", buildToolDefinition(tool),
                "user_question", userQuestion
        ));
        messages.add(ChatMessage.user(userPrompt));

        String raw = null;
        try {
            // 使用低随机性配置降低参数抽取波动，thinking 关闭以便获得可直接解析的 JSON 输出。
            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .temperature(0.1D)
                    .topP(0.3D)
                    .thinking(false)
                    .build();
            raw = llmService.chat(request);
            log.info("MCP 参数提取 LLM 响应: {}", raw);

            // 解析阶段会清洗响应、校验 JSON 对象，并只保留 MCP Tool schema 中声明的参数。
            Map<String, Object> extracted = parseJsonResponse(raw, tool);

            // LLM 未返回但 schema 声明了 default 的参数，在最终结果中补齐。
            fillDefaults(extracted, tool);

            log.info("MCP 参数提取完成, toolId: {}, 使用自定义提示词: {}, 参数: {}",
                    tool.name(), StrUtil.isNotBlank(customPromptTemplate), extracted);

            return extracted;
        } catch (JsonSyntaxException e) {
            log.warn("MCP 参数提取-JSON解析失败, toolId: {}, 响应: {}", tool.name(), raw, e);
            return buildDefaultParameters(tool);
        } catch (Exception e) {
            log.error("MCP 参数提取异常, toolId: {}", tool.name(), e);
            return buildDefaultParameters(tool);
        }
    }

    private Map<String, Object> buildDefaultParameters(Tool tool) {
        Map<String, Object> defaultParams = new HashMap<>();
        // 降级结果不猜测用户输入，只复用工具 schema 中显式定义的默认值。
        fillDefaults(defaultParams, tool);
        return defaultParams;
    }

    /**
     * 构建工具定义描述（供 LLM 理解）
     */
    @SuppressWarnings("unchecked")
    private String buildToolDefinition(Tool tool) {
        StringBuilder sb = new StringBuilder();
        sb.append("工具ID: ").append(tool.name()).append("\n");
        sb.append("功能描述: ").append(tool.description()).append("\n");
        sb.append("参数列表:\n");

        JsonSchema schema = tool.inputSchema();
        if (schema == null || schema.properties() == null) {
            return sb.toString();
        }

        List<String> requiredList = schema.required() != null ? schema.required() : List.of();

        // 将 JSON Schema 中与参数匹配直接相关的元数据压缩成自然语言，减少 Prompt 噪声。
        for (Map.Entry<String, Object> entry : schema.properties().entrySet()) {
            String paramName = entry.getKey();
            Map<String, Object> propDef = (Map<String, Object>) entry.getValue();

            String type = propDef.getOrDefault("type", "string").toString();
            boolean required = requiredList.contains(paramName);
            String description = propDef.getOrDefault("description", "").toString();
            Object defaultValue = propDef.get("default");
            Object enumValues = propDef.get("enum");

            sb.append("  - ").append(paramName);
            sb.append(" (类型: ").append(type);
            sb.append(required ? ", 必填" : ", 可选");
            sb.append("): ").append(description);

            if (defaultValue != null) {
                sb.append(" [默认值: ").append(defaultValue).append("]");
            }
            if (enumValues instanceof List<?> enumList && !enumList.isEmpty()) {
                String enumStr = enumList.stream().map(Object::toString).collect(Collectors.joining(", "));
                sb.append(" [可选值: ").append(enumStr).append("]");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 解析 LLM 返回的 JSON 响应
     */
    private Map<String, Object> parseJsonResponse(String raw, Tool tool) {
        if (StrUtil.isBlank(raw)) {
            return new HashMap<>();
        }
        // 兼容模型返回 ```json ... ``` 的情况，清洗后再交给 Gson 严格解析。
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        JsonElement element = JsonParser.parseString(cleaned);
        if (!element.isJsonObject()) {
            log.warn("LLM 返回的不是 JSON 对象: {}", raw);
            return new HashMap<>();
        }
        JsonObject obj = element.getAsJsonObject();
        Map<String, Object> result = new HashMap<>();

        Set<String> paramNames = tool.inputSchema() != null && tool.inputSchema().properties() != null
                ? tool.inputSchema().properties().keySet()
                : Set.of();

        // 参数匹配以 MCP Tool schema 为准，丢弃 LLM 额外生成的字段，避免污染工具调用入参。
        for (String paramName : paramNames) {
            if (obj.has(paramName) && !obj.get(paramName).isJsonNull()) {
                JsonElement value = obj.get(paramName);
                result.put(paramName, convertJsonElement(value));
            }
        }
        return result;
    }

    /**
     * 转换 JsonElement 为普通 Java 对象
     */
    private Object convertJsonElement(JsonElement element) {
        if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                // Gson 数字统一先按 double 读取，再尽量还原为整数类型，减少下游参数类型偏差。
                double d = primitive.getAsDouble();

                if (Double.isNaN(d)) {
                    return null;
                }

                if (d == Math.floor(d) && !Double.isInfinite(d)) {
                    if (d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
                        return (int) d;
                    } else if (d >= Long.MIN_VALUE && d <= Long.MAX_VALUE) {
                        return (long) d;
                    }
                }
                return d;
            } else if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            } else {
                return primitive.getAsString();
            }
        } else if (element.isJsonArray()) {
            // 复杂结构保持为 Java 集合类型，便于 MCP executor 直接消费。
            return gson.fromJson(element, List.class);
        } else if (element.isJsonObject()) {
            return gson.fromJson(element, LinkedHashMap.class);
        }
        return null;
    }

    /**
     * 填充默认值
     */
    @SuppressWarnings("unchecked")
    private void fillDefaults(Map<String, Object> params, Tool tool) {
        if (tool.inputSchema() == null || tool.inputSchema().properties() == null) {
            return;
        }

        for (Map.Entry<String, Object> entry : tool.inputSchema().properties().entrySet()) {
            String paramName = entry.getKey();
            Map<String, Object> propDef = (Map<String, Object>) entry.getValue();
            Object defaultValue = propDef.get("default");

            // 只补缺失参数，不覆盖 LLM 已从用户问题中提取到的显式值。
            if (!params.containsKey(paramName) && defaultValue != null) {
                params.put(paramName, defaultValue);
            }
        }
    }
}

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
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.infra.util.LogSafe;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 *     <li>把结果映射为三态：可调用（SUCCESS）/ 缺必填需澄清（NEED_CLARIFICATION）/ 提取失败（FAILED）。</li>
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
    public McpExtractionResult extractParameters(String userQuestion, Tool tool) {
        return extractParameters(userQuestion, tool, null);
    }

    @Override
    public McpExtractionResult extractParameters(String userQuestion, Tool tool, String customPromptTemplate) {
        // 无工具定义或无参数 schema 时，没有可供 LLM 匹配的参数空间，直接返回空参数。
        if (tool == null || tool.inputSchema() == null || CollUtil.isEmpty(tool.inputSchema().properties())) {
            return McpExtractionResult.success(new HashMap<>());
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

        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();

        // 提参质量直接决定工具调用是否正确，故走默认档（standard）而不是快速档；
        // 档位内已有多候选做传输容错，这里失败即判 FAILED、不调用工具
        McpExtractionResult result;
        try {
            result = validateMcpParams(llmService.chat(request), tool);
        } catch (Exception e) {
            log.warn("MCP 参数提取 LLM 调用失败, toolId: {}", tool.name(), e);
            result = McpExtractionResult.failed();
        }

        // 仅 SUCCESS 才补默认值并交由消费端调用；NEED_CLARIFICATION / FAILED 不调用工具，故不补
        if (result.status() == McpExtractionResult.Status.SUCCESS) {
            fillDefaults(result.params(), tool);
        }
        log.info("MCP 参数提取完成, toolId: {}, 使用自定义提示词: {}, 结局: {}, 参数: {}",
                tool.name(), StrUtil.isNotBlank(customPromptTemplate), result.status(), result.params());
        return result;
    }

    /**
     * 校验提参结果并映射为三态
     * <ul>
     *   <li>JSON 解析失败 / 空响应 / 非对象 / 值类型或枚举非法 → FAILED（模型未遵守协议，不调用工具）</li>
     *   <li>必填且无默认值的参数缺失或为 null → NEED_CLARIFICATION（用户确实没给，交给消费端追问）</li>
     *   <li>其余 → SUCCESS</li>
     * </ul>
     */
    private McpExtractionResult validateMcpParams(String raw, Tool tool) {
        log.info("MCP 参数提取 LLM 响应: {}", LogSafe.preview(raw));
        McpParse parsed;
        try {
            parsed = parseAndClassify(raw, tool);
        } catch (Exception e) {
            log.warn("MCP 参数提取响应解析失败, toolId: {}", tool.name(), e);
            return McpExtractionResult.failed();
        }

        if (!parsed.failReasons().isEmpty()) {
            log.warn("MCP 参数提取失败（模型未遵守协议 / 值非法）, toolId: {}, 问题: {}",
                    tool.name(), parsed.failReasons());
            return McpExtractionResult.failed();
        }
        if (!parsed.userMissing().isEmpty()) {
            log.warn("MCP 参数提取缺少必填参数（用户未提供，触发澄清）, toolId: {}, missing: {}",
                    tool.name(), parsed.userMissing());
            return McpExtractionResult.needClarification(parsed.params(), parsed.userMissing());
        }
        return McpExtractionResult.success(parsed.params());
    }

    /**
     * 按工具 schema 逐参数分类
     * <p>
     * 「模型省略 key」与「模型显式输出 null」在实践中不可区分，统一按「用户未提供」处理：
     * 必填且无默认值 → 澄清；非必填/有默认值 → 忽略，交由 {@link #fillDefaults} 兜底。
     * 值存在但类型/枚举非法一律判 FAILED（含可选字段）：静默丢弃会让过滤条件被无声移除
     */
    @SuppressWarnings("unchecked")
    private McpParse parseAndClassify(String raw, Tool tool) {
        Map<String, Object> params = new HashMap<>();
        List<String> failReasons = new ArrayList<>();
        List<String> userMissing = new ArrayList<>();

        JsonSchema schema = tool.inputSchema();
        Map<String, Object> properties = schema != null ? schema.properties() : null;
        if (properties == null || properties.isEmpty()) {
            return new McpParse(params, failReasons, userMissing);
        }
        List<String> required = schema.required() != null ? schema.required() : List.of();
        JsonObject obj = parseJsonObject(raw);

        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            String name = entry.getKey();
            Map<String, Object> propDef = entry.getValue() instanceof Map
                    ? (Map<String, Object>) entry.getValue() : Map.of();
            boolean isRequired = required.contains(name);
            boolean hasDefault = propDef.get("default") != null;

            boolean present = obj.has(name);
            boolean isNull = present && obj.get(name).isJsonNull();
            if (!present || isNull) {
                if (isRequired && !hasDefault) {
                    userMissing.add(name);
                }
                continue;
            }

            Optional<Object> coerced = coerceAndValidate(convertJsonElement(obj.get(name)), propDef);
            if (coerced.isPresent()) {
                params.put(name, coerced.get());
            } else {
                failReasons.add(name + "（值类型 / 枚举非法）");
            }
        }
        return new McpParse(params, failReasons, userMissing);
    }

    /**
     * 把 LLM 原始响应解析为 JSON 对象；非对象或空内容直接判为协议异常
     */
    private JsonObject parseJsonObject(String raw) {
        if (StrUtil.isBlank(raw)) {
            throw new IllegalArgumentException("响应对空");
        }
        JsonElement element = JsonParser.parseString(LLMResponseCleaner.stripMarkdownCodeFence(raw));
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("响应不是 JSON 对象");
        }
        return element.getAsJsonObject();
    }

    /**
     * 值类型与枚举校验
     * <p>
     * 保守但实用：字符串参数接受标量（数字/布尔转字符串，学号、编号常见此类表达）；
     * 数字/布尔/数组/对象要求类型匹配；存在 enum 时必须命中其中之一。命中不了即判非法
     */
    private Optional<Object> coerceAndValidate(Object value, Map<String, Object> propDef) {
        if (value == null) {
            return Optional.empty();
        }
        Object enumValues = propDef.get("enum");
        if (enumValues instanceof List<?> allowed && !allowed.isEmpty()) {
            String text = String.valueOf(value).trim();
            return allowed.stream().anyMatch(candidate -> String.valueOf(candidate).equals(text))
                    ? Optional.of(value)
                    : Optional.empty();
        }

        String type = Objects.toString(propDef.getOrDefault("type", "string"), "string");
        return switch (type) {
            case "integer", "number" -> value instanceof Number ? Optional.of(value) : Optional.empty();
            case "boolean" -> value instanceof Boolean ? Optional.of(value) : Optional.empty();
            case "array" -> value instanceof List ? Optional.of(value) : Optional.empty();
            case "object" -> value instanceof Map ? Optional.of(value) : Optional.empty();
            default -> Optional.of(value instanceof String ? value : String.valueOf(value));
        };
    }

    private record McpParse(Map<String, Object> params,
                            List<String> failReasons,
                            List<String> userMissing) {
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

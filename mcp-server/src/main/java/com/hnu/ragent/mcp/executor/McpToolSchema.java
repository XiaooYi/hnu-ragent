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

package com.hnu.ragent.mcp.executor;

import io.modelcontextprotocol.spec.McpSchema.JsonSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具入参 schema 构造器，把各执行器手拼的 {@code Map.of("type", "string", ...)} 收成一处
 * <p>
 * 只覆盖本仓工具实际用到的形态：三种标量类型、title/description/default/enum。
 * 需要数组或嵌套对象时再扩，不提前造无人调用的分支
 */
public final class McpToolSchema {

    private final Map<String, Object> properties = new LinkedHashMap<>();

    private final List<String> required = new ArrayList<>();

    private McpToolSchema() {
    }

    public static McpToolSchema object() {
        return new McpToolSchema();
    }

    public static Property string(String name, String description) {
        return new Property(name, "string", description);
    }

    public static Property integer(String name, String description) {
        return new Property(name, "integer", description);
    }

    public static Property number(String name, String description) {
        return new Property(name, "number", description);
    }

    /**
     * 必填参数，参数名会进 schema 的 required 列表
     */
    public McpToolSchema required(Property property) {
        assertPresent(property);
        required.add(property.name);
        return put(property);
    }

    public McpToolSchema optional(Property property) {
        return put(property);
    }

    /**
     * {@code additionalProperties} 恒为 false：schema 之外的键一律不认
     * <p>
     * 服务端不据此校验入参（SDK 的 JsonSchemaValidator 只管 outputSchema），这里纯粹是发给模型的约束，
     * 省掉它模型就可能自造参数名，而自造的键在 handleCall 里会被静默忽略
     */
    public JsonSchema build() {
        return new JsonSchema("object", unmodifiableOrdered(properties), List.copyOf(required),
                false, null, null);
    }

    /**
     * 只读但保序：{@code Map.copyOf} 会打乱迭代顺序，参数顺序一乱，发给模型的 schema 与确认卡的字段顺序
     * 每次启动都不一样
     */
    private static Map<String, Object> unmodifiableOrdered(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private McpToolSchema put(Property property) {
        assertPresent(property);
        if (properties.put(property.name, property.toMap()) != null) {
            throw new IllegalStateException("MCP 工具参数重名: " + property.name);
        }
        return this;
    }

    private static void assertPresent(Property property) {
        if (property == null) {
            throw new IllegalArgumentException("MCP 工具参数不允许为空");
        }
    }

    /**
     * 单个参数，name 与 description 强制在构造时给出
     * <p>
     * description 是模型判断该不该填这个参数的唯一依据，做成链式可选就会有人漏掉
     */
    public static final class Property {

        private final String name;

        private final Map<String, Object> attributes = new LinkedHashMap<>();

        private Property(String name, String type, String description) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("MCP 工具参数名不允许为空");
            }
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("MCP 工具参数缺少描述: " + name);
            }
            this.name = name;
            attributes.put("type", type);
            attributes.put("description", description);
        }

        /**
         * 展示标签，人工确认卡按它渲染字段名，未声明时前端回落成参数名
         */
        public Property title(String title) {
            attributes.put("title", title);
            return this;
        }

        /**
         * 取值白名单
         */
        public Property options(List<String> options) {
            attributes.put("enum", List.copyOf(options));
            return this;
        }

        public Property defaultTo(Object defaultValue) {
            attributes.put("default", defaultValue);
            return this;
        }

        private Map<String, Object> toMap() {
            return unmodifiableOrdered(attributes);
        }
    }
}

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

package com.hnu.ragent.agent.tool;

import java.util.List;

/**
 * 工具参数描述
 * <p>
 * description 是模型判断「该不该填这个参数」的唯一依据，构造时强制给出
 *
 * @param name        参数名（模型输出 arguments 时使用）
 * @param type        类型：string / integer / number / boolean
 * @param description 参数说明
 * @param required    是否必填
 * @param enumValues  取值白名单，可为空
 */
public record AgentToolParameter(String name,
                                 String type,
                                 String description,
                                 boolean required,
                                 List<String> enumValues) {

    public AgentToolParameter {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Agent 工具参数名不允许为空");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Agent 工具参数缺少描述: " + name);
        }
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
    }

    public static AgentToolParameter required(String name, String type, String description) {
        return new AgentToolParameter(name, type, description, true, List.of());
    }

    public static AgentToolParameter optional(String name, String type, String description) {
        return new AgentToolParameter(name, type, description, false, List.of());
    }

    public AgentToolParameter options(List<String> values) {
        return new AgentToolParameter(name, type, description, required, values);
    }

    /**
     * 渲染成提示词里的单行参数说明，例如 {@code query（string，必填）：检索关键词}
     */
    public String describe() {
        StringBuilder text = new StringBuilder(name)
                .append("（").append(type).append(required ? "，必填" : "，可选").append("）：")
                .append(description);
        if (!enumValues.isEmpty()) {
            text.append("，可选值：").append(String.join(" / ", enumValues));
        }
        return text.toString();
    }
}

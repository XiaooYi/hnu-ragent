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

package com.nageoffer.ai.ragent.agent.dto;

import java.util.Map;

/**
 * Agent 的一步：想的什么、调了哪个工具、看到什么
 * <p>
 * 供 SSE 展示工具进度与 trace 留痕；{@code toolId} 为空表示这一步只是模型思考后收口
 *
 * @param index       步骤序号，从 1 开始
 * @param thought     模型的思考（可为空）
 * @param toolId      调用的工具 id，无工具调用为 null
 * @param arguments   工具入参
 * @param observation 工具观察结果（工具调用失败时为错误提示）
 * @param latencyMs   本步耗时
 */
public record AgentStep(int index,
                        String thought,
                        String toolId,
                        Map<String, Object> arguments,
                        String observation,
                        long latencyMs) {

    public AgentStep {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public boolean hasToolCall() {
        return toolId != null && !toolId.isBlank();
    }
}

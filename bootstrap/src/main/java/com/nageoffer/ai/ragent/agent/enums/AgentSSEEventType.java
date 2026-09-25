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

package com.nageoffer.ai.ragent.agent.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 模式 SSE 事件协议
 * <p>
 * 与 RAG 版 {@code SSEEventType} 分立：Agent 多了工具进度与运行提示，事件语义不同，混用会让前端
 * 在两套语义之间反复判断
 */
@Getter
@AllArgsConstructor
public enum AgentSSEEventType {

    /**
     * 会话与运行元信息（会话 ID、消息 ID）
     */
    META("meta"),

    /**
     * 工具进度：{@code {step, toolId, status:start|end, arguments, observation, latencyMs}}
     */
    TOOL("tool"),

    /**
     * 正文消息（当前实现一次性下发最终回答）
     */
    MESSAGE("message"),

    /**
     * 运行提示（如达到步数上限），不落库
     */
    HINT("hint"),

    /**
     * 结束（携带结束原因与耗时）
     */
    FINISH("finish");

    private final String value;
}

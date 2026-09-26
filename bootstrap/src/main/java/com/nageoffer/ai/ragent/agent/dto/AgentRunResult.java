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

import java.util.List;
import java.util.Map;

/**
 * 一次 Agent 运行的结果
 *
 * @param answer          最终回答（达到步数上限时为最后一次观察结果兜底）
 * @param steps           过程步骤，供 SSE 展示与 trace 留痕
 * @param stopReason      结束原因
 * @param elapsedMs       总耗时
 * @param pendingCall     需要用户确认的写操作调用；仅当 stopReason=CONFIRM_REQUIRED 时非空
 */
public record AgentRunResult(String answer,
                             List<AgentStep> steps,
                             StopReason stopReason,
                             long elapsedMs,
                             PendingToolCall pendingCall) {

    public AgentRunResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public AgentRunResult(String answer, List<AgentStep> steps, StopReason stopReason, long elapsedMs) {
        this(answer, steps, stopReason, elapsedMs, null);
    }

    /**
     * 待确认的写操作调用
     *
     * @param toolId        工具 id
     * @param arguments     模型给出的入参
     * @param fieldLabels   参数名 → 人工可读说明（确认卡渲染用）
     * @param stepIndex     该调用发生在第几步
     */
    public record PendingToolCall(String toolId,
                                  Map<String, Object> arguments,
                                  Map<String, String> fieldLabels,
                                  int stepIndex) {

        public PendingToolCall {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
            fieldLabels = fieldLabels == null ? Map.of() : Map.copyOf(fieldLabels);
        }
    }

    /**
     * 结束原因
     */
    public enum StopReason {
        /** 模型给出了最终回答 */
        FINAL_ANSWER,
        /** 达到步数上限，用最后一次观察收口 */
        MAX_STEPS,
        /** 模型协议不合规（非 JSON），整段文本作为回答 */
        FALLBACK_TEXT,
        /** 命中写操作，等待用户确认后才执行 */
        CONFIRM_REQUIRED
    }
}

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

/**
 * 一次 Agent 运行的结果
 *
 * @param answer          最终回答（达到步数上限时为最后一次观察结果兜底）
 * @param steps           过程步骤，供 SSE 展示与 trace 留痕
 * @param stopReason      结束原因
 * @param elapsedMs       总耗时
 */
public record AgentRunResult(String answer,
                             List<AgentStep> steps,
                             StopReason stopReason,
                             long elapsedMs) {

    public AgentRunResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
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
        FALLBACK_TEXT
    }
}

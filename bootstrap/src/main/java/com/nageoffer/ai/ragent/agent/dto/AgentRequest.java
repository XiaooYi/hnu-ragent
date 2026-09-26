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

import lombok.Builder;

import java.util.List;

/**
 * 一次 Agent 运行的输入
 *
 * @param question       用户问题（已做查询改写时可传改写后的问题）
 * @param conversationId 会话 id，仅用于日志与后续记忆挂载，引擎本身不读会话内容
 * @param maxSteps       覆盖配置的步数上限，非正表示用配置值
 * @param memories       长期记忆召回结果，注入提示词；为空表示本次没有可用记忆
 */
@Builder
public record AgentRequest(String question,
                           String conversationId,
                           Integer maxSteps,
                           List<String> memories) {

    public AgentRequest {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Agent 问题不能为空");
        }
        memories = memories == null ? List.of() : List.copyOf(memories);
    }

    public int resolveMaxSteps(int fallback) {
        return maxSteps != null && maxSteps > 0 ? maxSteps : fallback;
    }
}

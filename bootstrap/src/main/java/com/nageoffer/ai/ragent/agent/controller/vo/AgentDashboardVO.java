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

package com.nageoffer.ai.ragent.agent.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Agent 运行指标视图（当前登录用户维度）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDashboardVO {

    /**
     * 统计窗口（天）
     */
    private long windowDays;

    private long conversations;

    private long messages;

    private long assistantMessages;

    /**
     * 结束状态分布：NORMAL / CONFIRM_PENDING / INTERRUPTED / FAILED / UNKNOWN
     */
    private Map<String, Long> statusCounts;

    /**
     * 助手消息平均耗时（毫秒）
     */
    private long avgDurationMs;

    /**
     * 生效中的长期记忆条数
     */
    private long activeMemories;

    private List<ToolUsage> toolUsage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ToolUsage {

        private String toolId;

        private long calls;

        private long avgLatencyMs;
    }
}

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

package com.nageoffer.ai.ragent.agent.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具使用统计：从消息 blocks 现算，非法输入不报错
 */
class AgentToolUsageAggregatorTest {

    @Test
    @DisplayName("按工具聚合调用次数与平均耗时，按次数降序")
    void aggregatesByTool() {
        List<AgentToolUsageAggregator.ToolUsage> usages = AgentToolUsageAggregator.aggregate(List.of(
                "[{\"type\":\"tool\",\"toolId\":\"knowledge_search\",\"latencyMs\":100},"
                        + "{\"type\":\"tool\",\"toolId\":\"youcom_search\",\"latencyMs\":300}]",
                "[{\"type\":\"tool\",\"toolId\":\"knowledge_search\",\"latencyMs\":200}]"));

        assertEquals(2, usages.size());
        assertEquals("knowledge_search", usages.get(0).toolId());
        assertEquals(2, usages.get(0).calls());
        assertEquals(150, usages.get(0).avgLatencyMs());
        assertEquals("youcom_search", usages.get(1).toolId());
        assertEquals(1, usages.get(1).calls());
        assertEquals(300, usages.get(1).avgLatencyMs());
    }

    @Test
    @DisplayName("空输入、非法 JSON、缺 toolId 的条目都被安全跳过")
    void toleratesBadInput() {
        assertTrue(AgentToolUsageAggregator.aggregate(null).isEmpty());
        assertTrue(AgentToolUsageAggregator.aggregate(List.of()).isEmpty());
        assertTrue(AgentToolUsageAggregator.aggregate(List.of("not-json")).isEmpty());
        assertTrue(AgentToolUsageAggregator.aggregate(List.of("{}")).isEmpty());
        assertTrue(AgentToolUsageAggregator.aggregate(List.of(
                "[{\"type\":\"tool\"},{\"toolId\":\"\"},{\"toolId\":\"knowledge_search\"}]")).size() == 1);
    }

    @Test
    @DisplayName("缺耗时按 0 计，不影响调用次数统计")
    void countsWithoutLatency() {
        List<AgentToolUsageAggregator.ToolUsage> usages = AgentToolUsageAggregator.aggregate(List.of(
                "[{\"toolId\":\"knowledge_search\"}]"));

        assertEquals(1, usages.size());
        assertEquals(1, usages.get(0).calls());
        assertEquals(0, usages.get(0).avgLatencyMs());
    }
}

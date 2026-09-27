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

package com.hnu.ragent.agent.admin;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具使用统计
 * <p>
 * 数据来源是助手消息的 {@code blocks} 列（工具步骤 JSON 数组）：不额外建统计表，
 * 直接从已有事实聚合，避免「统计口径与明细不一致」这类最难查的问题
 * <p>
 * 纯函数：输入是消息里的 blocks 字符串列表，输出是按调用次数排序的工具统计；非法 JSON 跳过不报错
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AgentToolUsageAggregator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 汇总工具调用次数与平均耗时（按次数降序）
     */
    public static List<ToolUsage> aggregate(List<String> blocksJsonList) {
        Map<String, long[]> counters = new LinkedHashMap<>();
        Map<String, Long> latencySums = new LinkedHashMap<>();
        if (blocksJsonList == null || blocksJsonList.isEmpty()) {
            return List.of();
        }
        for (String blocksJson : blocksJsonList) {
            for (ToolStep step : parseSteps(blocksJson)) {
                counters.computeIfAbsent(step.toolId(), key -> new long[1])[0]++;
                latencySums.merge(step.toolId(), Math.max(0L, step.latencyMs()), Long::sum);
            }
        }
        List<ToolUsage> usages = new ArrayList<>();
        counters.forEach((toolId, count) -> usages.add(new ToolUsage(
                toolId,
                count[0],
                count[0] == 0 ? 0L : latencySums.getOrDefault(toolId, 0L) / count[0])));
        usages.sort(Comparator.comparingLong(ToolUsage::calls).reversed()
                .thenComparing(ToolUsage::toolId));
        return usages;
    }

    /**
     * 解析单条消息的 blocks；非法 JSON 或结构不符时返回空列表
     */
    static List<ToolStep> parseSteps(String blocksJson) {
        if (StrUtil.isBlank(blocksJson)) {
            return List.of();
        }
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(blocksJson);
        } catch (Exception e) {
            log.debug("工具块不是合法 JSON，已跳过: {}", blocksJson);
            return List.of();
        }
        if (!root.isArray()) {
            return List.of();
        }
        List<ToolStep> steps = new ArrayList<>();
        for (JsonNode node : root) {
            String toolId = node.path("toolId").asText("");
            if (StrUtil.isBlank(toolId)) {
                continue;
            }
            steps.add(new ToolStep(toolId, node.path("latencyMs").asLong(0L)));
        }
        return steps;
    }

    /**
     * 单个工具调用的最小信息
     */
    record ToolStep(String toolId, long latencyMs) {
    }

    /**
     * 工具统计结果
     *
     * @param toolId       工具 id
     * @param calls        调用次数
     * @param avgLatencyMs 平均耗时
     */
    public record ToolUsage(String toolId, long calls, long avgLatencyMs) {
    }
}

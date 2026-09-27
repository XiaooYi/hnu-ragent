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

package com.hnu.ragent.agent.service;

import com.hnu.ragent.agent.dao.entity.AgentMessageDO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行指标：状态分布与平均耗时口径
 */
class AgentDashboardServiceTest {

    private final AgentDashboardService service = new AgentDashboardService(null, null, null);

    @Test
    @DisplayName("状态分布按 message_status 计数，缺失归入 UNKNOWN")
    void countsStatuses() {
        Map<String, Long> counts = service.statusCounts(List.of(
                message("NORMAL", 100L),
                message("NORMAL", 200L),
                message("CONFIRM_PENDING", 0L),
                message(null, 50L)));

        assertEquals(2L, counts.get("NORMAL"));
        assertEquals(1L, counts.get("CONFIRM_PENDING"));
        assertEquals(1L, counts.get("UNKNOWN"));
    }

    @Test
    @DisplayName("平均耗时只统计有耗时的消息")
    void averagesDuration() {
        assertEquals(150L, service.averageDuration(List.of(
                message("NORMAL", 100L),
                message("NORMAL", 200L),
                message("NORMAL", null))));

        assertEquals(0L, service.averageDuration(List.of()));
        assertEquals(0L, service.averageDuration(null));
        assertEquals(0L, service.averageDuration(List.of(message("NORMAL", 0L))));
    }

    @Test
    @DisplayName("空列表返回空分布")
    void handlesEmpty() {
        assertTrue(service.statusCounts(null).isEmpty());
        assertTrue(service.statusCounts(List.of()).isEmpty());
    }

    private AgentMessageDO message(String status, Long durationMs) {
        return AgentMessageDO.builder()
                .role("assistant")
                .messageStatus(status)
                .durationMs(durationMs)
                .build();
    }
}

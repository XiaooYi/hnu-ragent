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

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hnu.ragent.agent.admin.AgentToolUsageAggregator;
import com.hnu.ragent.agent.controller.vo.AgentDashboardVO;
import com.hnu.ragent.agent.dao.entity.AgentConversationDO;
import com.hnu.ragent.agent.dao.entity.AgentMemoryDO;
import com.hnu.ragent.agent.dao.entity.AgentMessageDO;
import com.hnu.ragent.agent.dao.mapper.AgentConversationMapper;
import com.hnu.ragent.agent.dao.mapper.AgentMemoryMapper;
import com.hnu.ragent.agent.dao.mapper.AgentMessageMapper;
import com.hnu.ragent.framework.context.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 运行指标
 * <p>
 * 口径：**只看当前登录用户自己的数据**。Agent 管理页在管理台里，但会话、消息与记忆都是用户私有数据，
 * 指标不跨用户聚合，避免「普通用户看到别人问过什么」
 * <p>
 * 不建统计表：会话数、消息数、状态分布都在既有表里，工具使用从消息的 {@code blocks} 列现算（窗口内条数很小），
 * 换来的是口径与明细永远一致
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentDashboardService {

    private static final long MAX_WINDOW_DAYS = 90L;

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentMemoryMapper memoryMapper;

    /**
     * 汇总窗口内的运行指标
     *
     * @param days 统计窗口（天），非正取默认 7，上限 90
     */
    public AgentDashboardVO summary(int days) {
        long windowDays = days > 0 ? Math.min(days, MAX_WINDOW_DAYS) : 7L;
        Date since = new Date(System.currentTimeMillis() - windowDays * 24 * 60 * 60 * 1000);
        String userId = currentUserId();

        Long conversations = conversationMapper.selectCount(Wrappers.lambdaQuery(AgentConversationDO.class)
                .eq(AgentConversationDO::getUserId, userId)
                .eq(AgentConversationDO::getDeleted, 0)
                .ge(AgentConversationDO::getCreateTime, since));
        List<AgentMessageDO> assistantMessages = messageMapper.selectList(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getUserId, userId)
                .eq(AgentMessageDO::getDeleted, 0)
                .eq(AgentMessageDO::getRole, "assistant")
                .ge(AgentMessageDO::getCreateTime, since));
        Long totalMessages = messageMapper.selectCount(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getUserId, userId)
                .eq(AgentMessageDO::getDeleted, 0)
                .ge(AgentMessageDO::getCreateTime, since));
        Long activeMemories = memoryMapper.selectCount(Wrappers.lambdaQuery(AgentMemoryDO.class)
                .eq(AgentMemoryDO::getUserId, userId)
                .isNull(AgentMemoryDO::getInvalidAt));

        return AgentDashboardVO.builder()
                .windowDays(windowDays)
                .conversations(conversations == null ? 0L : conversations)
                .messages(totalMessages == null ? 0L : totalMessages)
                .assistantMessages((long) assistantMessages.size())
                .statusCounts(statusCounts(assistantMessages))
                .avgDurationMs(averageDuration(assistantMessages))
                .activeMemories(activeMemories == null ? 0L : activeMemories)
                .toolUsage(AgentToolUsageAggregator.aggregate(assistantMessages.stream()
                                .map(AgentMessageDO::getBlocks)
                                .filter(StrUtil::isNotBlank)
                                .toList()).stream()
                        .map(usage -> AgentDashboardVO.ToolUsage.builder()
                                .toolId(usage.toolId())
                                .calls(usage.calls())
                                .avgLatencyMs(usage.avgLatencyMs())
                                .build())
                        .toList())
                .build();
    }

    /**
     * 消息结束状态分布（NORMAL / CONFIRM_PENDING / INTERRUPTED / FAILED）
     */
    Map<String, Long> statusCounts(List<AgentMessageDO> messages) {
        Map<String, Long> counts = new LinkedHashMap<>();
        if (messages == null) {
            return counts;
        }
        for (AgentMessageDO message : messages) {
            String status = StrUtil.blankToDefault(message.getMessageStatus(), "UNKNOWN");
            counts.merge(status, 1L, Long::sum);
        }
        return counts;
    }

    /**
     * 平均耗时：只统计有耗时的助手消息
     */
    long averageDuration(List<AgentMessageDO> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0L;
        }
        long sum = 0L;
        long count = 0L;
        for (AgentMessageDO message : messages) {
            if (message.getDurationMs() != null && message.getDurationMs() > 0) {
                sum += message.getDurationMs();
                count++;
            }
        }
        return count == 0 ? 0L : sum / count;
    }

    private String currentUserId() {
        return StrUtil.blankToDefault(UserContext.getUserId(), "anonymous");
    }
}

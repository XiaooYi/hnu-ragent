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

package com.nageoffer.ai.ragent.agent.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentConversationDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentConversationMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * Agent 会话与消息持久化实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentConversationServiceImpl implements AgentConversationService {

    private static final int TITLE_MAX_LENGTH = 30;

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;

    @Override
    public String ensureConversation(String conversationId, String question) {
        String userId = currentUserId();
        String targetId = StrUtil.isNotBlank(conversationId) ? conversationId : IdUtil.getSnowflakeNextIdStr();
        AgentConversationDO existing = findConversation(targetId, userId);
        if (existing != null) {
            return targetId;
        }
        conversationMapper.insert(AgentConversationDO.builder()
                .conversationId(targetId)
                .userId(userId)
                .title(buildTitle(question))
                .lastTime(new Date())
                .deleted(0)
                .build());
        log.info("Agent 会话已创建, conversationId={}, userId={}", targetId, userId);
        return targetId;
    }

    @Override
    public List<AgentConversationDO> listConversations() {
        return conversationMapper.selectList(Wrappers.lambdaQuery(AgentConversationDO.class)
                .eq(AgentConversationDO::getUserId, currentUserId())
                .eq(AgentConversationDO::getDeleted, 0)
                .orderByDesc(AgentConversationDO::getLastTime));
    }

    @Override
    public List<AgentMessageDO> listMessages(String conversationId) {
        return messageMapper.selectList(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getConversationId, conversationId)
                .eq(AgentMessageDO::getUserId, currentUserId())
                .eq(AgentMessageDO::getDeleted, 0)
                .orderByAsc(AgentMessageDO::getCreateTime)
                .orderByAsc(AgentMessageDO::getId));
    }

    @Override
    public String saveUserMessage(String conversationId, String question) {
        AgentMessageDO message = AgentMessageDO.builder()
                .conversationId(conversationId)
                .userId(currentUserId())
                .role("user")
                .content(question)
                .messageStatus("NORMAL")
                .deleted(0)
                .build();
        messageMapper.insert(message);
        touchConversation(conversationId);
        return message.getId();
    }

    @Override
    public void saveAssistantMessage(String conversationId, String replyToMessageId, String content,
                                     String blocksJson, String messageStatus, long durationMs) {
        AgentMessageDO message = AgentMessageDO.builder()
                .conversationId(conversationId)
                .userId(currentUserId())
                .role("assistant")
                .content(content)
                .blocks(blocksJson)
                .replyToMessageId(replyToMessageId)
                .messageStatus(StrUtil.blankToDefault(messageStatus, "NORMAL"))
                .durationMs(durationMs)
                .deleted(0)
                .build();
        messageMapper.insert(message);
        touchConversation(conversationId);
    }

    @Override
    public void deleteConversation(String conversationId) {
        AgentConversationDO conversation = findConversation(conversationId, currentUserId());
        if (conversation == null) {
            return;
        }
        conversationMapper.deleteById(conversation.getId());
        messageMapper.delete(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getConversationId, conversationId)
                .eq(AgentMessageDO::getUserId, currentUserId()));
    }

    private AgentConversationDO findConversation(String conversationId, String userId) {
        return conversationMapper.selectOne(Wrappers.lambdaQuery(AgentConversationDO.class)
                .eq(AgentConversationDO::getConversationId, conversationId)
                .eq(AgentConversationDO::getUserId, userId)
                .eq(AgentConversationDO::getDeleted, 0));
    }

    private void touchConversation(String conversationId) {
        AgentConversationDO conversation = findConversation(conversationId, currentUserId());
        if (conversation == null) {
            return;
        }
        conversation.setLastTime(new Date());
        conversationMapper.updateById(conversation);
    }

    private String buildTitle(String question) {
        String title = StrUtil.blankToDefault(question, "新会话").replaceAll("\\s+", " ").trim();
        return title.length() > TITLE_MAX_LENGTH ? title.substring(0, TITLE_MAX_LENGTH) : title;
    }

    private String currentUserId() {
        return StrUtil.blankToDefault(UserContext.getUserId(), "anonymous");
    }
}

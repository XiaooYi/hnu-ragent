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

package com.hnu.ragent.agent.service.impl;

import com.hnu.ragent.agent.dao.entity.AgentConversationDO;
import com.hnu.ragent.agent.dao.entity.AgentMessageDO;
import com.hnu.ragent.agent.dao.mapper.AgentConversationMapper;
import com.hnu.ragent.agent.dao.mapper.AgentMessageMapper;
import com.hnu.ragent.framework.context.LoginUser;
import com.hnu.ragent.framework.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 会话持久化：会话创建 / 标题截断 / 消息落库与删除
 */
class AgentConversationServiceImplTest {

    private final AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
    private final AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);

    private AgentConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentConversationServiceImpl(conversationMapper, messageMapper);
        UserContext.set(LoginUser.builder().userId("u-1").username("tester").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("会话不存在时创建，标题取问题前 30 字")
    void createsConversationWithTruncatedTitle() {
        when(conversationMapper.selectOne(any())).thenReturn(null);

        String conversationId = service.ensureConversation(null, "转专业需要哪些材料".repeat(6));

        assertNotNull(conversationId);
        ArgumentCaptor<AgentConversationDO> captor = ArgumentCaptor.forClass(AgentConversationDO.class);
        verify(conversationMapper).insert(captor.capture());
        assertEquals("u-1", captor.getValue().getUserId());
        assertEquals(30, captor.getValue().getTitle().length());
        assertEquals(0, captor.getValue().getDeleted());
    }

    @Test
    @DisplayName("会话已存在时复用，不重复建会话")
    void reusesExistingConversation() {
        when(conversationMapper.selectOne(any())).thenReturn(AgentConversationDO.builder()
                .id("row-1").conversationId("conv-1").userId("u-1").title("已有会话").deleted(0).build());

        assertEquals("conv-1", service.ensureConversation("conv-1", "新问题"));
        verify(conversationMapper, never()).insert(any(AgentConversationDO.class));
    }

    @Test
    @DisplayName("用户消息落库并刷新会话最后时间")
    void savesUserMessageAndTouchesConversation() {
        when(conversationMapper.selectOne(any())).thenReturn(AgentConversationDO.builder()
                .id("row-1").conversationId("conv-1").userId("u-1").title("会话").deleted(0).build());

        String messageId = service.saveUserMessage("conv-1", "问题");

        ArgumentCaptor<AgentMessageDO> captor = ArgumentCaptor.forClass(AgentMessageDO.class);
        verify(messageMapper).insert(captor.capture());
        assertEquals("user", captor.getValue().getRole());
        assertEquals("NORMAL", captor.getValue().getMessageStatus());
        assertEquals(captor.getValue().getId(), messageId, "返回的即是落库消息的 ID");
        verify(conversationMapper).updateById(any(AgentConversationDO.class));
    }

    @Test
    @DisplayName("助手消息带工具块与耗时落库")
    void savesAssistantMessageWithBlocks() {
        when(conversationMapper.selectOne(any())).thenReturn(AgentConversationDO.builder()
                .id("row-1").conversationId("conv-1").userId("u-1").title("会话").deleted(0).build());

        service.saveAssistantMessage("conv-1", "msg-1", "回答", "[{\"type\":\"tool\"}]", "NORMAL", 1234L);

        ArgumentCaptor<AgentMessageDO> captor = ArgumentCaptor.forClass(AgentMessageDO.class);
        verify(messageMapper).insert(captor.capture());
        AgentMessageDO message = captor.getValue();
        assertEquals("assistant", message.getRole());
        assertEquals("msg-1", message.getReplyToMessageId());
        assertTrue(message.getBlocks().contains("tool"));
        assertEquals(1234L, message.getDurationMs());
    }

    @Test
    @DisplayName("删除会话时同时逻辑删消息")
    void deletesConversationWithMessages() {
        when(conversationMapper.selectOne(any())).thenReturn(AgentConversationDO.builder()
                .id("row-1").conversationId("conv-1").userId("u-1").title("会话").deleted(0).build());

        service.deleteConversation("conv-1");

        verify(conversationMapper).deleteById("row-1");
        verify(messageMapper).delete(any());
    }

    @Test
    @DisplayName("会话不存在时删除是空操作")
    void deleteIsNoopWhenMissing() {
        when(conversationMapper.selectOne(any())).thenReturn(null);

        service.deleteConversation("missing");

        verify(conversationMapper, never()).deleteById(any(String.class));
        verify(messageMapper, never()).delete(any());
    }
}

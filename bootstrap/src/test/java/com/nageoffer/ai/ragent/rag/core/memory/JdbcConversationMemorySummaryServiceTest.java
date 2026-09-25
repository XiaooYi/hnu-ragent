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

package com.nageoffer.ai.ragent.rag.core.memory;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationMessageDO;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationSummaryDO;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import com.nageoffer.ai.ragent.rag.service.ConversationMessageService;
import com.nageoffer.ai.ragent.rag.service.bo.ConversationSummaryBO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话摘要的刷新边界
 * <p>
 * 原文窗口起点只用于判断「已有摘要是否仍覆盖窗口」，摘要截止边界放在窗口中部：
 * 两者合一会让窗口每右移一轮都生成新摘要，也就是每轮都多调一次 LLM
 */
@ExtendWith(MockitoExtension.class)
class JdbcConversationMemorySummaryServiceTest {

    private static final String CONVERSATION_ID = "conv-1";
    private static final String USER_ID = "user-1";

    @Mock
    private ConversationGroupService conversationGroupService;
    @Mock
    private ConversationMessageService conversationMessageService;
    @Mock
    private LLMService llmService;
    @Mock
    private PromptTemplateLoader promptTemplateLoader;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    private MemoryProperties memoryProperties;
    private JdbcConversationMemorySummaryService service;

    @BeforeEach
    void setUp() {
        memoryProperties = new MemoryProperties();
        memoryProperties.setSummaryEnabled(true);
        memoryProperties.setSummaryStartTurns(4);
        memoryProperties.setHistoryKeepTurns(6);
        memoryProperties.setSummaryMaxChars(400);

        // 用同步执行器：把异步摘要变成可断言的同步行为
        Executor directExecutor = Runnable::run;
        service = new JdbcConversationMemorySummaryService(
                conversationGroupService,
                conversationMessageService,
                memoryProperties,
                llmService,
                promptTemplateLoader,
                redissonClient,
                directExecutor);
    }

    @Test
    @DisplayName("首次满足触发轮数时生成摘要，截止边界取窗口中部")
    void createsFirstSummaryAtWindowMiddle() {
        prepareLockAndCount();
        // 倒序列表：最新在前；窗口为 [9,8,7,6,5,4]，中部下标 (6-1)/2 = 2 → 边界 id 7
        List<ConversationMessageDO> latestTurns = List.of(
                userTurn("9"), userTurn("8"), userTurn("7"),
                userTurn("6"), userTurn("5"), userTurn("4"));
        when(conversationGroupService.findLatestSummary(CONVERSATION_ID, USER_ID)).thenReturn(null);
        when(conversationGroupService.listLatestUserOnlyMessages(CONVERSATION_ID, USER_ID, 6))
                .thenReturn(latestTurns);
        when(conversationGroupService.listMessagesBetweenIds(eq(CONVERSATION_ID), eq(USER_ID), eq(null), eq("7")))
                .thenReturn(List.of(assistantTurn("5"), userTurn("6"), userTurn("7")));
        when(promptTemplateLoader.render(anyString(), any())).thenReturn("摘要提示词");
        // 摘要属于高频低风险任务：固定走快速档
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("校园问答摘要");

        service.compressIfNeeded(CONVERSATION_ID, USER_ID, ChatMessage.assistant("回答", null, null));

        verify(llmService).chat(any(ChatRequest.class), eq(Tier.FAST));
        ArgumentCaptor<ConversationSummaryBO> captor = ArgumentCaptor.forClass(ConversationSummaryBO.class);
        verify(conversationMessageService).addMessageSummary(captor.capture());
        assertEquals("校园问答摘要", captor.getValue().getContent());
        assertNotNull(captor.getValue().getLastMessageId());
    }

    @Test
    @DisplayName("已有摘要边界仍在窗口内时不重复调用 LLM（窗口每滑一轮不再重算）")
    void skipsWhenExistingSummaryStillCoversWindow() {
        prepareLockAndCount();
        List<ConversationMessageDO> latestTurns = List.of(
                userTurn("9"), userTurn("8"), userTurn("7"),
                userTurn("6"), userTurn("5"), userTurn("4"));
        when(conversationGroupService.findLatestSummary(CONVERSATION_ID, USER_ID)).thenReturn(summary("8"));
        when(conversationGroupService.listLatestUserOnlyMessages(CONVERSATION_ID, USER_ID, 6))
                .thenReturn(latestTurns);

        service.compressIfNeeded(CONVERSATION_ID, USER_ID, ChatMessage.assistant("回答", null, null));

        // 摘要边界 8 ≥ 窗口起点 4：摘要已覆盖窗口起点，本轮的增量摘要没有新内容
        verify(conversationGroupService, never())
                .listMessagesBetweenIds(anyString(), anyString(), any(), anyString());
        verify(llmService, never()).chat(any(ChatRequest.class), any(Tier.class));
    }

    @Test
    @DisplayName("摘要边界滑出窗口后重新生成摘要（避免历史空洞）")
    void refreshesAfterSummaryBoundarySlidesOutOfWindow() {
        prepareLockAndCount();
        List<ConversationMessageDO> latestTurns = List.of(
                userTurn("9"), userTurn("8"), userTurn("7"),
                userTurn("6"), userTurn("5"), userTurn("4"));
        when(conversationGroupService.findLatestSummary(CONVERSATION_ID, USER_ID)).thenReturn(summary("3"));
        when(conversationGroupService.listLatestUserOnlyMessages(CONVERSATION_ID, USER_ID, 6))
                .thenReturn(latestTurns);
        when(conversationGroupService.listMessagesBetweenIds(eq(CONVERSATION_ID), eq(USER_ID), eq("3"), eq("7")))
                .thenReturn(List.of(userTurn("6"), userTurn("7")));
        when(promptTemplateLoader.render(anyString(), any())).thenReturn("摘要提示词");
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("更新后的摘要");

        service.compressIfNeeded(CONVERSATION_ID, USER_ID, ChatMessage.assistant("回答", null, null));

        // 摘要边界 3 < 窗口起点 4：窗口里已有未被摘要覆盖的增量，刷新一次
        verify(llmService).chat(any(ChatRequest.class), eq(Tier.FAST));
        verify(conversationMessageService).addMessageSummary(any());
    }

    @Test
    @DisplayName("未达触发轮数时不生成摘要")
    void skipsBeforeTriggerTurns() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(conversationGroupService.countUserMessages(CONVERSATION_ID, USER_ID)).thenReturn(2L);

        service.compressIfNeeded(CONVERSATION_ID, USER_ID, ChatMessage.assistant("回答", null, null));

        verify(llmService, never()).chat(any(ChatRequest.class), any(Tier.class));
        verify(conversationMessageService, never()).addMessageSummary(any());
    }

    private void prepareLockAndCount() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(conversationGroupService.countUserMessages(CONVERSATION_ID, USER_ID)).thenReturn(10L);
    }

    private ConversationMessageDO userTurn(String id) {
        return ConversationMessageDO.builder()
                .id(id)
                .role("user")
                .content("问题" + id)
                .createTime(new Date())
                .build();
    }

    private ConversationMessageDO assistantTurn(String id) {
        return ConversationMessageDO.builder()
                .id(id)
                .role("assistant")
                .content("回答" + id)
                .createTime(new Date())
                .build();
    }

    private ConversationSummaryDO summary(String lastMessageId) {
        ConversationSummaryDO summary = new ConversationSummaryDO();
        summary.setConversationId(CONVERSATION_ID);
        summary.setUserId(USER_ID);
        summary.setContent("既有摘要");
        summary.setLastMessageId(lastMessageId);
        return summary;
    }
}

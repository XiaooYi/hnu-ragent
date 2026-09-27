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

import com.hnu.ragent.agent.dao.entity.AgentConversationDO;
import com.hnu.ragent.agent.dao.entity.AgentMessageDO;

import java.util.List;

/**
 * Agent 会话与消息持久化
 */
public interface AgentConversationService {

    /**
     * 确保会话存在（不存在则按问题生成标题创建），返回会话 ID
     */
    String ensureConversation(String conversationId, String question);

    /**
     * 当前用户的会话列表（按最后消息时间倒序）
     */
    List<AgentConversationDO> listConversations();

    /**
     * 会话内的消息（按创建顺序）
     */
    List<AgentMessageDO> listMessages(String conversationId);

    /**
     * 落库一条用户消息，返回消息 ID
     */
    String saveUserMessage(String conversationId, String question);

    /**
     * 落库一条助手消息（含工具块与结束状态）
     */
    void saveAssistantMessage(String conversationId, String replyToMessageId, String content,
                              String blocksJson, String messageStatus, long durationMs);

    /**
     * 删除会话（逻辑删）
     */
    void deleteConversation(String conversationId);
}

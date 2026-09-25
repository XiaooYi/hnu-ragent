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

package com.nageoffer.ai.ragent.agent.controller;

import com.nageoffer.ai.ragent.agent.controller.vo.AgentConversationVO;
import com.nageoffer.ai.ragent.agent.controller.vo.AgentMessageVO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentConversationDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * Agent 对话接口
 * <p>
 * 仅在 {@code ai.agent.enabled=true} 时存在：未启用时路由不存在，避免暴露一个必然失败的接口
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.agent", name = "enabled", havingValue = "true")
public class AgentChatController {

    private final AgentChatService agentChatService;
    private final AgentConversationService conversationService;
    private final RAGDefaultProperties ragDefaultProperties;

    /**
     * Agent 流式对话
     */
    @GetMapping(value = "/agent/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@RequestParam String question,
                           @RequestParam(required = false) String conversationId) {
        SseEmitter emitter = new SseEmitter(ragDefaultProperties.getSseTimeoutMs());
        agentChatService.streamChat(question, conversationId, emitter);
        return emitter;
    }

    /**
     * 会话列表
     */
    @GetMapping("/agent/conversations")
    public Result<List<AgentConversationVO>> conversations() {
        List<AgentConversationVO> list = conversationService.listConversations().stream()
                .map(this::toConversationVO)
                .toList();
        return Results.success(list);
    }

    /**
     * 会话消息（含工具块）
     */
    @GetMapping("/agent/conversations/{conversationId}/messages")
    public Result<List<AgentMessageVO>> messages(@PathVariable String conversationId) {
        List<AgentMessageVO> list = conversationService.listMessages(conversationId).stream()
                .map(this::toMessageVO)
                .toList();
        return Results.success(list);
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/agent/conversations/{conversationId}")
    public Result<Void> delete(@PathVariable String conversationId) {
        conversationService.deleteConversation(conversationId);
        return Results.success();
    }

    private AgentConversationVO toConversationVO(AgentConversationDO conversation) {
        return AgentConversationVO.builder()
                .conversationId(conversation.getConversationId())
                .title(conversation.getTitle())
                .lastTime(conversation.getLastTime())
                .createTime(conversation.getCreateTime())
                .build();
    }

    private AgentMessageVO toMessageVO(AgentMessageDO message) {
        return AgentMessageVO.builder()
                .id(message.getId())
                .conversationId(message.getConversationId())
                .role(message.getRole())
                .content(message.getContent())
                .blocks(message.getBlocks())
                .replyToMessageId(message.getReplyToMessageId())
                .messageStatus(message.getMessageStatus())
                .durationMs(message.getDurationMs())
                .createTime(message.getCreateTime())
                .build();
    }
}

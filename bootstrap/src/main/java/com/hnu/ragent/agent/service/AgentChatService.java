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

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * Agent 对话
 */
public interface AgentChatService {

    /**
     * 跑一轮 Agent 并用 SSE 下发过程事件
     *
     * @param question       用户问题
     * @param conversationId 会话 ID，为空则由服务端新建
     * @param emitter        SSE 通道
     */
    void streamChat(String question, String conversationId, SseEmitter emitter);

    /**
     * 用户确认后执行写操作，返回工具观察结果
     * <p>
     * 入参以用户提交为准；工具必须仍在目录内（白名单与启用状态生效）
     */
    String executeConfirmed(String toolId, Map<String, Object> arguments);
}

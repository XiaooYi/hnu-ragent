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

package com.hnu.ragent.rag.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.hnu.ragent.framework.context.UserContext;
import com.hnu.ragent.framework.web.SseEmitterSender;
import com.hnu.ragent.infra.chat.StreamCallback;
import com.hnu.ragent.rag.dto.ErrorPayload;
import com.hnu.ragent.rag.enums.SSEEventType;
import com.hnu.ragent.rag.service.ratelimit.ChatQueueLimiter;
import com.hnu.ragent.rag.service.RAGChatService;
import com.hnu.ragent.rag.service.handler.StreamCallbackFactory;
import com.hnu.ragent.rag.service.handler.StreamTaskManager;
import com.hnu.ragent.rag.service.pipeline.StreamChatContext;
import com.hnu.ragent.rag.service.pipeline.StreamChatPipeline;
import com.hnu.ragent.rag.trace.StreamChatTraceRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 对话服务默认实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private final StreamChatPipeline chatPipeline;
    private final ChatQueueLimiter chatQueueLimiter;
    private final StreamCallbackFactory callbackFactory;
    private final StreamChatTraceRunner traceRunner;
    private final StreamTaskManager taskManager;

    @Override
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        String taskId = IdUtil.getSnowflakeNextIdStr();
        StreamCallback callback;
        try {
            callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);
        } catch (Exception ex) {
            // 事件处理器初始化失败（meta 可能尚未发出）：直接以 SSE error 事件收尾
            log.error("对话事件处理器初始化失败，conversationId：{}", actualConversationId, ex);
            completeWithErrorEvent(emitter);
            return;
        }

        chatQueueLimiter.enqueue(question, actualConversationId, emitter,
                () -> {
                    try {
                        traceRunner.run(question, actualConversationId, taskId, callback, traceAware -> {
                            StreamChatContext ctx = StreamChatContext.builder()
                                    .question(question)
                                    .conversationId(actualConversationId)
                                    .taskId(taskId)
                                    .deepThinking(Boolean.TRUE.equals(deepThinking))
                                    .userId(UserContext.getUserId())
                                    .callback(traceAware)
                                    .build();
                            chatPipeline.execute(ctx);
                        });
                    } catch (Exception ex) {
                        // 管线在执行线程内同步抛错时任务会静默消失、连接悬挂直到超时；
                        // 统一交给事件处理器下发 error 终态事件并正常关闭连接
                        log.error("对话管线执行失败，conversationId：{}", actualConversationId, ex);
                        callback.onError(ex);
                    }
                });
    }

    /**
     * 事件处理器不可用时的兜底：尽力补发 error 事件并关闭连接
     */
    private void completeWithErrorEvent(SseEmitter emitter) {
        SseEmitterSender sender = new SseEmitterSender(emitter);
        sender.sendEvent(SSEEventType.ERROR.value(), new ErrorPayload("回答生成失败，请稍后重试"));
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        sender.complete();
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }
}

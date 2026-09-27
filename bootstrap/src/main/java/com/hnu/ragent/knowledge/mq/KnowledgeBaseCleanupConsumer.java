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

package com.hnu.ragent.knowledge.mq;

import com.hnu.ragent.framework.exception.ServiceException;
import com.hnu.ragent.framework.mq.MessageWrapper;
import com.hnu.ragent.knowledge.mq.event.KnowledgeBaseCleanupEvent;
import com.hnu.ragent.rag.core.vector.VectorStoreAdmin;
import com.hnu.ragent.rag.core.vector.keyword.KeywordIndexService;
import com.hnu.ragent.rag.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 知识库删除清理 MQ 消费者
 * <p>
 * 负责异步回收知识库独占的底层物理资源：向量空间、bucket、关键词索引
 * <p>
 * 各清理项 best-effort 互不影响，存在失败项则抛异常触发重试；所有操作均幂等，重试安全
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "knowledge-base-cleanup_topic${unique-name:}",
        consumerGroup = "knowledge-base-cleanup_cg${unique-name:}"
)
public class KnowledgeBaseCleanupConsumer implements RocketMQListener<MessageWrapper<KnowledgeBaseCleanupEvent>> {

    private final VectorStoreAdmin vectorStoreAdmin;
    private final FileStorageService fileStorageService;

    /**
     * 关键词索引实现是可选的（rag.keyword.type=none 时不存在），惰性解析避免条件装配顺序问题
     */
    private final ObjectProvider<KeywordIndexService> keywordIndexServiceProvider;

    @Override
    public void onMessage(MessageWrapper<KnowledgeBaseCleanupEvent> message) {
        KnowledgeBaseCleanupEvent event = message.getBody();
        if (event == null || !StringUtils.hasText(event.getCollectionName())) {
            log.warn("[消费者] 知识库清理事件缺少 collectionName，跳过：{}", event);
            return;
        }
        String collectionName = event.getCollectionName();

        log.info("[消费者] 开始清理知识库物理资源，kbId={}, collectionName={}, operator={}",
                event.getKbId(), collectionName, event.getOperator());

        boolean allSucceeded = true;

        try {
            vectorStoreAdmin.dropVectorSpace(collectionName);
        } catch (Exception e) {
            allSucceeded = false;
            log.error("清理向量空间失败，collectionName={}", collectionName, e);
        }

        try {
            fileStorageService.deleteBucket(collectionName);
        } catch (Exception e) {
            allSucceeded = false;
            log.error("删除 bucket 失败，bucket={}", collectionName, e);
        }

        KeywordIndexService keywordIndexService = keywordIndexServiceProvider.getIfAvailable();
        if (keywordIndexService != null) {
            try {
                keywordIndexService.deleteByCollection(collectionName);
            } catch (Exception e) {
                allSucceeded = false;
                log.error("清理关键词索引失败，collectionName={}", collectionName, e);
            }
        }

        if (!allSucceeded) {
            // 不能静默吞掉失败：否则底层资源永久残留，表现为「删了库还能被检索到」
            throw new ServiceException("知识库物理资源清理存在失败项，触发重试");
        }
        log.info("[消费者] 知识库物理资源清理完成，collectionName={}", collectionName);
    }
}

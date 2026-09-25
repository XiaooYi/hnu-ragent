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

package com.nageoffer.ai.ragent.knowledge.mq;

import com.nageoffer.ai.ragent.framework.mq.MessageWrapper;
import com.nageoffer.ai.ragent.framework.mq.producer.DelegatingTransactionListener;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.mq.event.KnowledgeBaseCleanupEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseCleanupTransactionCheckerTest {

    private final KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);

    @Test
    @DisplayName("载荷类型声明正确，回查才能按类型反序列化")
    void declaresBodyType() {
        assertEquals(KnowledgeBaseCleanupEvent.class, checker().bodyType());
    }

    @Test
    @DisplayName("知识库已逻辑删除（查不到）视为本地事务已提交")
    void commitsWhenKnowledgeBaseAlreadyDeleted() {
        when(knowledgeBaseMapper.selectById("kb-1")).thenReturn(null);

        assertTrue(checker().check(wrapperWithKbId("kb-1")));
    }

    @Test
    @DisplayName("知识库仍可见视为本地事务未提交，消息不投递")
    void rollsBackWhenKnowledgeBaseStillVisible() {
        when(knowledgeBaseMapper.selectById("kb-1")).thenReturn(new KnowledgeBaseDO());

        assertFalse(checker().check(wrapperWithKbId("kb-1")));
    }

    @Test
    @DisplayName("载荷缺少 kbId 时判为未提交，避免误投递")
    void rollsBackWhenKbIdMissing() {
        assertFalse(checker().check(wrapperWithKbId(null)));
    }

    private KnowledgeBaseCleanupTransactionChecker checker() {
        KnowledgeBaseCleanupTransactionChecker checker = new KnowledgeBaseCleanupTransactionChecker(
                knowledgeBaseMapper, new DelegatingTransactionListener());
        ReflectionTestUtils.setField(checker, "cleanupTopic", "knowledge-base-cleanup_topic");
        return checker;
    }

    private MessageWrapper<KnowledgeBaseCleanupEvent> wrapperWithKbId(String kbId) {
        return MessageWrapper.<KnowledgeBaseCleanupEvent>builder()
                .body(KnowledgeBaseCleanupEvent.builder().kbId(kbId).collectionName("kb_coll").build())
                .build();
    }
}

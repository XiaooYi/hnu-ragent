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

package com.nageoffer.ai.ragent.framework.mq.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RocketMQProducerAdapterTest {

    private static final String TOPIC = "knowledge-document-chunk_topic";

    private RocketMQTemplate rocketMQTemplate;
    private DelegatingTransactionListener transactionListener;
    private RocketMQProducerAdapter producerAdapter;

    @BeforeEach
    void setUp() {
        rocketMQTemplate = mock(RocketMQTemplate.class);
        transactionListener = new DelegatingTransactionListener();
        producerAdapter = new RocketMQProducerAdapter(rocketMQTemplate, transactionListener);

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
        ReflectionTestUtils.setField(transactionListener, "transactionManager", transactionManager);
        ReflectionTestUtils.setField(transactionListener, "objectMapper", new ObjectMapper());
    }

    @Test
    void sendFailureRemovesLocalTransactionCallback() {
        RuntimeException sendFailure = new RuntimeException("broker unavailable");
        AtomicReference<Message<?>> sentMessage = new AtomicReference<>();
        doAnswer(invocation -> {
            sentMessage.set(invocation.getArgument(1));
            throw sendFailure;
        }).when(rocketMQTemplate).sendMessageInTransaction(eq(TOPIC), any(Message.class), isNull());

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> producerAdapter.sendInTransaction(
                        TOPIC, "message-key", "document chunk", "payload",
                        ignored -> {
                        }));

        assertSame(sendFailure, thrown);
        assertEquals(0, pendingTransactions().size());
        assertFalse(sentMessage.get() == null);
    }

    @Test
    void nonSendOkResultRemovesLocalTransactionCallback() {
        AtomicBoolean localTransactionInvoked = new AtomicBoolean();
        doReturn(transactionSendResult(SendStatus.FLUSH_DISK_TIMEOUT))
                .when(rocketMQTemplate).sendMessageInTransaction(eq(TOPIC), any(Message.class), isNull());

        producerAdapter.sendInTransaction(
                TOPIC, "message-key", "document chunk", "payload",
                ignored -> localTransactionInvoked.set(true));

        assertFalse(localTransactionInvoked.get());
        assertEquals(0, pendingTransactions().size());
    }

    @Test
    void successfulSendKeepsCallbackUntilLocalTransactionRuns() {
        AtomicReference<Message<?>> sentMessage = new AtomicReference<>();
        doAnswer(invocation -> {
            sentMessage.set(invocation.getArgument(1));
            return transactionSendResult(SendStatus.SEND_OK);
        }).when(rocketMQTemplate).sendMessageInTransaction(eq(TOPIC), any(Message.class), isNull());

        producerAdapter.sendInTransaction(
                TOPIC, "message-key", "document chunk", "payload",
                ignored -> {
                });

        assertEquals(1, pendingTransactions().size());

        RocketMQLocalTransactionState state = transactionListener.executeLocalTransaction(sentMessage.get(), null);

        assertEquals(RocketMQLocalTransactionState.COMMIT, state);
        assertEquals(0, pendingTransactions().size());
    }

    private static TransactionSendResult transactionSendResult(SendStatus sendStatus) {
        TransactionSendResult result = new TransactionSendResult();
        result.setSendStatus(sendStatus);
        result.setLocalTransactionState(LocalTransactionState.UNKNOW);
        result.setMsgId("msg-1");
        return result;
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<String, Consumer<Object>> pendingTransactions() {
        return (ConcurrentMap<String, Consumer<Object>>)
                ReflectionTestUtils.getField(transactionListener, "localTransactionMap");
    }
}

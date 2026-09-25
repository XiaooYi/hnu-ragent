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

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户取消不该污染模型健康度，也不该继续降级
 */
class ModelRoutingExecutorCancellationTest {

    private final ModelHealthStore healthStore = mock(ModelHealthStore.class);
    private final ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);

    @Test
    @DisplayName("候选抛取消异常时：不改健康度、不降级，直接抛取消")
    void cancellationStopsFallbackAndKeepsHealth() {
        when(healthStore.allowCall(anyString())).thenReturn(new ModelHealthStore.CallPermit("m1", 0L));
        AtomicInteger invocations = new AtomicInteger();

        assertThrows(CancellationException.class, () -> executor.executeWithFallback(
                ModelCapability.CHAT,
                List.of(target("m1"), target("m2")),
                target -> "client",
                (client, target) -> {
                    invocations.incrementAndGet();
                    throw new CancellationException("用户停止");
                }));

        assertEquals(1, invocations.get(), "取消后不得继续尝试下一个候选");
        verify(healthStore, never()).markFailure(anyString());
        verify(healthStore, never()).markSuccess(anyString());
    }

    @Test
    @DisplayName("普通失败仍然标记不健康并降级到下一个候选")
    void plainFailureStillFallsBack() {
        when(healthStore.allowCall(anyString())).thenReturn(new ModelHealthStore.CallPermit("m1", 0L));
        AtomicInteger invocations = new AtomicInteger();

        String result = executor.executeWithFallback(
                ModelCapability.CHAT,
                List.of(target("m1"), target("m2")),
                target -> "client",
                (client, target) -> {
                    if (invocations.incrementAndGet() == 1) {
                        throw new IllegalStateException("模型不可用");
                    }
                    return "ok";
                });

        assertEquals("ok", result);
        assertEquals(2, invocations.get());
        verify(healthStore).markFailure("m1");
        verify(healthStore).markSuccess("m2");
        assertFalse(Thread.currentThread().isInterrupted());
    }

    private ModelTarget target(String id) {
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId(id);
        candidate.setProvider("bailian");
        candidate.setModel(id);
        return new ModelTarget(id, candidate, new AIModelProperties.ProviderConfig());
    }
}

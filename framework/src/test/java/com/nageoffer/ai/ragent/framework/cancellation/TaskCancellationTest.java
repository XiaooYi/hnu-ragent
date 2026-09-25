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

package com.nageoffer.ai.ragent.framework.cancellation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取消判定：区分「用户取消」与真实失败
 */
class TaskCancellationTest {

    @Test
    @DisplayName("中断标记本身就是取消信号")
    void interruptedThreadIsCancellation() {
        try {
            Thread.currentThread().interrupt();
            assertTrue(TaskCancellation.isCancelled());
            assertTrue(TaskCancellation.isCancellation(null));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("异常链上的 InterruptedException / CancellationException 都算取消")
    void detectsCancellationInCauseChain() {
        assertTrue(TaskCancellation.isCancellation(new InterruptedException("interrupted")));
        assertTrue(TaskCancellation.isCancellation(new CancellationException("cancelled")));
        assertTrue(TaskCancellation.isCancellation(
                new CompletionException(new CancellationException("cancelled"))),
                "包装在 CompletionException 里的取消同样要认出来");
    }

    @Test
    @DisplayName("普通业务异常不是取消")
    void plainFailureIsNotCancellation() {
        assertFalse(TaskCancellation.isCancellation(null));
        assertFalse(TaskCancellation.isCancellation(new IllegalStateException("模型不可用")));
        assertFalse(TaskCancellation.isCancellation(new RuntimeException(new IllegalStateException("网络错误"))));
    }

    @Test
    @DisplayName("归一化为取消异常时补回被清掉的中断标记")
    void restoresInterruptFlag() {
        try {
            CancellationException cancellation = TaskCancellation.asCancellation(
                    new RuntimeException(new InterruptedException("interrupted")));

            assertNotNull(cancellation);
            assertTrue(Thread.currentThread().isInterrupted(),
                    "InterruptedException 会清除中断标记，必须补回来，否则上游判据失灵");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("已是取消异常时原样返回，并保留原始 cause")
    void keepsOriginalCancellation() {
        CancellationException original = new CancellationException("cancelled");

        assertSame(original, TaskCancellation.asCancellation(original));
        assertNotNull(TaskCancellation.asCancellation(new IllegalStateException("boom")).getCause());
    }
}

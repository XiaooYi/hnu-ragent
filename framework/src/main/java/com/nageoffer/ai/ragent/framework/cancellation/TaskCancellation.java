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

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.concurrent.CancellationException;

/**
 * 判定一次失败是不是用户取消
 * <p>
 * 只服务于「不该把取消记成故障」的出口：用户停止后取消会以线程中断或取消异常两种形态冒泡，
 * 若不识别，正常模型会被 {@code markFailure} 推进熔断、工具调用会被记成 error 级噪声
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TaskCancellation {

    /**
     * 下游「降级返回而非抛出」时，出口只剩中断标记可判
     */
    public static boolean isCancelled() {
        return Thread.currentThread().isInterrupted();
    }

    /**
     * 遍历异常因果链识别取消：{@link InterruptedException} 或 {@link CancellationException}
     */
    public static boolean isCancellation(Throwable error) {
        if (isCancelled()) {
            return true;
        }
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException || current instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归一化为取消异常，并补回被 {@link InterruptedException} 清掉的中断标记
     * <p>
     * {@link InterruptedException} 抛出时会清除线程中断状态，不补回来上游的
     * {@link #isCancelled()} 判据就会失灵
     */
    public static CancellationException asCancellation(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (cause instanceof CancellationException cancellation) {
            return cancellation;
        }
        CancellationException cancellation = new CancellationException("Task cancelled");
        if (cause != null) {
            cancellation.initCause(cause);
        }
        return cancellation;
    }
}

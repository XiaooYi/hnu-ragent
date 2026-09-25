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

package com.nageoffer.ai.ragent.core.parser.mineru;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MinerU 解析入口的分布式许可语义
 * <p>
 * 并发上限在多实例间共享（Redis 信号量），许可覆盖整条解析链路并在 finally 释放
 */
class MinerUDocumentParserTest {

    private final MinerUClient minerUClient = mock(MinerUClient.class);
    private final MinerUPollingExecutor pollingExecutor = mock(MinerUPollingExecutor.class);
    private final MinerUResultUnpacker resultUnpacker = mock(MinerUResultUnpacker.class);
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RPermitExpirableSemaphore semaphore = mock(RPermitExpirableSemaphore.class);

    private MinerUProperties properties;
    private MinerUDocumentParser parser;

    @BeforeEach
    void setUp() {
        properties = new MinerUProperties();
        properties.setSemaphoreName("rag:mineru:parse");
        properties.setConcurrencyLimit(5);
        properties.setMaxWaitSeconds(30);
        properties.setLeaseSeconds(900);
        when(redissonClient.getPermitExpirableSemaphore("rag:mineru:parse")).thenReturn(semaphore);
        parser = new MinerUDocumentParser(minerUClient, pollingExecutor, resultUnpacker, properties, redissonClient);
    }

    @Test
    @DisplayName("启动时按配置写入许可数（跨实例共用配额）")
    void initializesPermitsOnStartup() {
        parser.initSemaphore();

        verify(semaphore).setPermits(5);
    }

    @Test
    @DisplayName("拿不到许可时快速失败，不调用 MinerU")
    void failsFastWhenPermitUnavailable() throws InterruptedException {
        when(semaphore.tryAcquire(30L, 900L, TimeUnit.SECONDS)).thenReturn(null);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> parser.parseStructured("pdf-bytes".getBytes(), "application/pdf", Map.of()));

        assertTrue(exception.getMessage().contains("解析任务过多"), exception.getMessage());
        verifyNoInteractions(minerUClient, pollingExecutor, resultUnpacker);
    }

    @Test
    @DisplayName("解析中途失败时许可被释放（不占坑）")
    void releasesPermitOnFailure() throws InterruptedException {
        when(semaphore.tryAcquire(30L, 900L, TimeUnit.SECONDS)).thenReturn("permit-1");
        when(minerUClient.requestUpload(any())).thenThrow(new IllegalStateException("MinerU 不可用"));

        assertThrows(IllegalStateException.class,
                () -> parser.parseStructured("pdf-bytes".getBytes(), "application/pdf", Map.of()));

        verify(semaphore).tryRelease("permit-1");
    }

    @Test
    @DisplayName("输入为空时直接失败，不占用许可")
    void skipsSemaphoreOnEmptyContent() throws InterruptedException {
        assertThrows(ServiceException.class,
                () -> parser.parseStructured(new byte[0], "application/pdf", Map.of()));

        verify(semaphore, never()).tryAcquire(anyLong(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("释放失败（许可已按租约过期）只告警，不掩盖业务结果")
    void toleratesExpiredPermit() throws InterruptedException {
        when(semaphore.tryAcquire(30L, 900L, TimeUnit.SECONDS)).thenReturn("permit-1");
        when(semaphore.tryRelease("permit-1")).thenReturn(false);
        when(minerUClient.requestUpload(any())).thenThrow(new IllegalStateException("MinerU 不可用"));

        assertThrows(IllegalStateException.class,
                () -> parser.parseStructured("pdf-bytes".getBytes(), "application/pdf", Map.of()));

        verify(semaphore).tryRelease(eq("permit-1"));
    }

    @Test
    @DisplayName("等待许可被中断时恢复中断位并抛出业务异常")
    void restoresInterruptFlag() throws InterruptedException {
        when(semaphore.tryAcquire(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("interrupted"));

        try {
            ServiceException exception = assertThrows(ServiceException.class,
                    () -> parser.parseStructured("pdf-bytes".getBytes(), "application/pdf", Map.of()));
            assertTrue(exception.getMessage().contains("被中断"), exception.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(), "中断位必须被恢复");
        } finally {
            // 清掉中断位，避免影响后续用例
            Thread.interrupted();
        }
        verifyNoInteractions(minerUClient);
        verify(semaphore, never()).tryRelease(anyString());
    }
}

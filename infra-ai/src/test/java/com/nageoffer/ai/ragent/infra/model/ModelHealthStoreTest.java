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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 熔断半开探测名额的所有权语义
 * <p>
 * 中断路径既不成功也不失败，名额必须可由持有者显式释放，否则该模型永久不可用
 */
class ModelHealthStoreTest {

    private static final String MODEL_ID = "qwen3-max";

    private AIModelProperties properties;
    private ModelHealthStore store;

    @BeforeEach
    void setUp() {
        AIModelProperties.Selection selection = new AIModelProperties.Selection();
        selection.setFailureThreshold(1);
        selection.setOpenDurationMs(0L);
        properties = new AIModelProperties();
        properties.setSelection(selection);
        store = new ModelHealthStore(properties);
    }

    @Test
    @DisplayName("CLOSED 态发普通凭证（token=0），无需释放")
    void closedStateGrantsPlainPermit() {
        ModelHealthStore.CallPermit permit = store.allowCall(MODEL_ID);

        assertNotNull(permit);
        assertEquals(0L, permit.halfOpenToken());
        store.releaseHalfOpenPermit(permit);
        assertFalse(store.isUnavailable(MODEL_ID));
    }

    @Test
    @DisplayName("HALF_OPEN 只放一个探测：已有探测在跑时再次申请被拒绝")
    void halfOpenAllowsSingleProbe() {
        store.markFailure(MODEL_ID);           // 失败阈值 1 + 熔断时长为 0 → 立即可探测

        ModelHealthStore.CallPermit first = store.allowCall(MODEL_ID);
        assertNotNull(first);
        assertTrue(first.halfOpenToken() > 0L, "半开探测凭证必须带 token");
        assertTrue(store.isUnavailable(MODEL_ID), "探测在跑期间模型不可用");

        assertNull(store.allowCall(MODEL_ID), "同一时刻只允许一个探测");
    }

    @Test
    @DisplayName("中断路径释放名额后可以再次探测（不会永久不可用）")
    void releasingPermitAllowsNextProbe() {
        store.markFailure(MODEL_ID);
        ModelHealthStore.CallPermit permit = store.allowCall(MODEL_ID);
        assertNotNull(permit);

        store.releaseHalfOpenPermit(permit);

        assertFalse(store.isUnavailable(MODEL_ID), "释放后应恢复可探测");
        ModelHealthStore.CallPermit next = store.allowCall(MODEL_ID);
        assertNotNull(next);
        assertTrue(next.halfOpenToken() > permit.halfOpenToken(), "新一轮探测应是新凭证");
    }

    @Test
    @DisplayName("旧凭证不会释放新一轮探测名额")
    void stalePermitDoesNotReleaseNewProbe() {
        store.markFailure(MODEL_ID);
        ModelHealthStore.CallPermit stale = store.allowCall(MODEL_ID);
        assertNotNull(stale);
        store.releaseHalfOpenPermit(stale);
        ModelHealthStore.CallPermit current = store.allowCall(MODEL_ID);
        assertNotNull(current);

        store.releaseHalfOpenPermit(stale);

        assertTrue(store.isUnavailable(MODEL_ID), "旧凭证不得放掉正在跑的新探测");
        store.releaseHalfOpenPermit(current);
        assertFalse(store.isUnavailable(MODEL_ID));
    }

    @Test
    @DisplayName("探测成功后熔断关闭")
    void markSuccessClosesCircuit() {
        store.markFailure(MODEL_ID);
        ModelHealthStore.CallPermit permit = store.allowCall(MODEL_ID);
        assertNotNull(permit);

        store.markSuccess(MODEL_ID);

        assertFalse(store.isUnavailable(MODEL_ID));
    }
}

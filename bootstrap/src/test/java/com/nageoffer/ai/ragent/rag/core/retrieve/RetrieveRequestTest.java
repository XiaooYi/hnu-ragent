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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrieveRequestTest {

    @Test
    @DisplayName("只有旧单库参数时回退为单元素列表")
    void fallsBackToLegacySingleCollection() {
        RetrieveRequest request = RetrieveRequest.builder().collectionName("kb_a").build();

        assertEquals(List.of("kb_a"), request.getEffectiveCollectionNames());
    }

    @Test
    @DisplayName("多库参数清洗后保序去重")
    void normalizesMultipleCollections() {
        RetrieveRequest request = RetrieveRequest.builder()
                .collectionName("kb_legacy")
                .collectionNames(Arrays.asList(" kb_a ", "", "kb_b", "kb_a"))
                .build();

        assertEquals(List.of("kb_a", "kb_b"), request.getEffectiveCollectionNames());
    }

    @Test
    @DisplayName("未指定任何库时返回空列表：实现层据此直接返回空结果，不发查询")
    void emptyWhenNothingConfigured() {
        assertTrue(RetrieveRequest.builder().build().getEffectiveCollectionNames().isEmpty());
    }
}

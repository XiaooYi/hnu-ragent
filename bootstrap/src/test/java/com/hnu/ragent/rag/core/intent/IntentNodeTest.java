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

package com.hnu.ragent.rag.core.intent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentNodeTest {

    @Test
    @DisplayName("只有旧单值字段时回退为单元素列表（旧缓存与旧数据兼容）")
    void fallsBackToLegacySingleCollection() {
        IntentNode node = IntentNode.builder().collectionName("kb_teaching").build();

        assertEquals(List.of("kb_teaching"), node.getEffectiveCollectionNames());
    }

    @Test
    @DisplayName("新字段优先，并做 trim、去空、去重且保序")
    void normalizesAndDeduplicates() {
        IntentNode node = IntentNode.builder()
                .collectionName("kb_legacy")
                .collectionNames(Arrays.asList(" kb_a ", "kb_b", "kb_a", "  ", null))
                .build();

        assertEquals(List.of("kb_a", "kb_b"), node.getEffectiveCollectionNames());
    }

    @Test
    @DisplayName("新旧字段都为空时返回空列表：调用方据此跳过检索")
    void emptyWhenNoCollectionConfigured() {
        IntentNode node = IntentNode.builder().collectionName("   ").build();

        assertTrue(node.getEffectiveCollectionNames().isEmpty());
    }
}

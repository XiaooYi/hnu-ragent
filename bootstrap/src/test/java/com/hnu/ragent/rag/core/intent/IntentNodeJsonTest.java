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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnu.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 意图节点缓存往返：计算型属性不得写进 JSON，历史脏缓存必须能读回
 */
class IntentNodeJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    @DisplayName("序列化不含计算属性，往返后 Collection 归一结果一致")
    void roundTripsCollectionNamesWithoutSerializingComputedProperty() throws Exception {
        IntentNode node = IntentNode.builder()
                .id("insurance")
                .collectionNames(List.of("insurance", " claims ", "insurance"))
                .build();

        String json = objectMapper.writeValueAsString(node);
        IntentNode restored = objectMapper.readValue(json, IntentNode.class);

        assertFalse(json.contains("effectiveCollectionNames"), json);
        assertEquals(List.of("insurance", "claims"), restored.getEffectiveCollectionNames());
    }

    @Test
    @DisplayName("历史脏缓存（含计算属性）按真实字段还原，不报错")
    void ignoresComputedPropertyFromExistingCacheEntry() throws Exception {
        String json = """
                {
                  "id": "insurance",
                  "collectionNames": ["insurance"],
                  "effectiveCollectionNames": ["stale"]
                }
                """;

        IntentNode restored = objectMapper.readValue(json, IntentNode.class);

        assertEquals(List.of("insurance"), restored.getEffectiveCollectionNames());
    }

    @Test
    @DisplayName("旧单值字段兜底与布尔计算属性的往返一致性")
    void keepsLegacyFallbackAndBooleanFlagsStable() throws Exception {
        IntentNode legacy = IntentNode.builder()
                .id("legacy")
                .collectionName(" legacy_store ")
                .kind(IntentKind.MCP)
                .build();

        IntentNode restored = objectMapper.readValue(objectMapper.writeValueAsString(legacy), IntentNode.class);

        assertEquals(List.of("legacy_store"), restored.getEffectiveCollectionNames());
        assertTrue(restored.isMCP());
        assertFalse(restored.isKB());
        assertTrue(restored.isLeaf());
    }
}

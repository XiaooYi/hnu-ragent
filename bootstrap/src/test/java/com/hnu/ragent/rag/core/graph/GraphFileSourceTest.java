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

package com.hnu.ragent.rag.core.graph;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 图谱来源标识编解码：右锚定末段数字，库名互为前缀也不串库
 */
class GraphFileSourceTest {

    @Test
    @DisplayName("encode / parse 互为逆运算")
    void roundTrip() {
        String encoded = GraphFileSource.encode("kb_hr", "123456");
        assertEquals("kb_hr_123456", encoded);

        GraphFileSource parsed = GraphFileSource.parse(encoded);
        assertNotNull(parsed);
        assertEquals("kb_hr", parsed.collectionName());
        assertEquals("123456", parsed.docId());
    }

    @Test
    @DisplayName("容忍目录前缀与服务端追加的扩展名")
    void toleratesPrefixAndExtension() {
        GraphFileSource parsed = GraphFileSource.parse("/data/uploads/kb_hr_123456.txt");
        assertNotNull(parsed);
        assertEquals("kb_hr", parsed.collectionName());
        assertEquals("123456", parsed.docId());

        GraphFileSource multiExtension = GraphFileSource.parse("kb_teaching_99.tar.gz");
        assertNotNull(multiExtension);
        assertEquals("kb_teaching", multiExtension.collectionName());
        assertEquals("99", multiExtension.docId());
    }

    @Test
    @DisplayName("库名互为前缀时按最右数字锚定，不串库")
    void avoidsPrefixAmbiguity() {
        GraphFileSource parsed = GraphFileSource.parse("kb_hr_2026_123");
        assertNotNull(parsed);
        assertEquals("kb_hr_2026", parsed.collectionName(), "整个前缀都应视作库名");
        assertEquals("123", parsed.docId());
    }

    @Test
    @DisplayName("不符合编码的 file_path 返回 null")
    void returnsNullOnInvalidInput() {
        assertNull(GraphFileSource.parse(null));
        assertNull(GraphFileSource.parse(""));
        assertNull(GraphFileSource.parse("no-digit-suffix.txt"));
        assertNull(GraphFileSource.parse("123456"));
        assertNull(GraphFileSource.parse("_123456"));
    }
}

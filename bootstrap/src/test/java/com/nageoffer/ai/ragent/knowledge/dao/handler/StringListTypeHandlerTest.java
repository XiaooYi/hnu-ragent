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

package com.nageoffer.ai.ragent.knowledge.dao.handler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@code List<String>} JSONB 类型处理器的空值语义
 * <p>
 * 关键契约：SQL {@code NULL} 必须读回 {@code null}，因为它与"空数组"表达的是两件不同的事
 * （未生成 vs 已生成但无内容）。把两者压成同一个值会让三态字段在上层退化成两态，
 * 例如推荐追问会把"从未生成"误判成"已生成且无追问"，从而永远不再调模型。
 */
@ExtendWith(MockitoExtension.class)
class StringListTypeHandlerTest {

    private static final String COLUMN = "recommended_questions";

    private final StringListTypeHandler handler = new StringListTypeHandler();

    @Mock
    private ResultSet resultSet;

    @Test
    void nullColumnIsReadBackAsNullNotAsEmptyList() throws SQLException {
        when(resultSet.getString(COLUMN)).thenReturn(null);

        assertThat(handler.getNullableResult(resultSet, COLUMN)).isNull();
    }

    @Test
    void invalidJsonDegradesToEmptyListInsteadOfFailingTheRow() throws SQLException {
        when(resultSet.getString(COLUMN)).thenReturn("{not json");

        assertThat(handler.getNullableResult(resultSet, COLUMN)).isEmpty();
    }

    @Test
    void jsonArrayIsParsedInOrder() throws SQLException {
        when(resultSet.getString(COLUMN)).thenReturn("[\"a\",\"b\"]");

        assertThat(handler.getNullableResult(resultSet, COLUMN)).containsExactly("a", "b");
    }
}

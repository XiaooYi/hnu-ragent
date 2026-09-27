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

package com.hnu.ragent.rag.core.vector;

import com.hnu.ragent.rag.config.RAGDefaultProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PG 共享表的「销毁向量空间」= 删除该 collection 的向量行（不动共享 HNSW 索引）
 */
class PgVectorStoreAdminTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PgVectorStoreAdmin admin = new PgVectorStoreAdmin(jdbcTemplate, new RAGDefaultProperties());

    @Test
    @DisplayName("按 collection 删除残留向量行")
    void deletesRowsOfCollection() {
        when(jdbcTemplate.update(
                "DELETE FROM t_knowledge_vector WHERE metadata->>'collection_name' = ?", "kb_teaching"))
                .thenReturn(3);

        admin.dropVectorSpace("kb_teaching");

        verify(jdbcTemplate).update(
                "DELETE FROM t_knowledge_vector WHERE metadata->>'collection_name' = ?", "kb_teaching");
    }

    @Test
    @DisplayName("collection 名为空时不做任何删除")
    void skipsBlankCollection() {
        admin.dropVectorSpace(null);
        admin.dropVectorSpace("   ");

        verify(jdbcTemplate, never()).update(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Object[].class));
    }
}

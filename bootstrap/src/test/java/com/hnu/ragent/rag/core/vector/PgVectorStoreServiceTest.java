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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code t_knowledge_vector} 的中文写入与检索契约验证。
 *
 * <p>
 * 表结构以 {@code resources/database/schema_pg.sql} 为准，即 {@code id / content / metadata / embedding vector(1536)}：
 * 写入语句沿用 {@link PgVectorStoreService} 的写法，检索语句沿用 {@code PgRetrieverService} 的
 * {@code metadata->>'collection_name'} 过滤加余弦距离排序。用例结束后删除本次写入的行，不在目标库留下残留数据。
 * </p>
 */
@SpringBootTest
public class PgVectorStoreServiceTest {

    /**
     * 与 {@code schema_pg.sql} 中 {@code embedding vector(1536)} 的维度保持一致
     */
    private static final int EMBEDDING_DIMENSION = 1536;

    private static final String COLLECTION_NAME = "unit_test_collection";

    private static final String CHUNK_ID = "test_chunk_001";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void testChineseCharacterInsertion() {
        String content = "这是一段中文测试内容，包含各种字符：你好世界！";
        String metadata = "{\"collection_name\":\"" + COLLECTION_NAME + "\"}";
        String vectorLiteral = buildVectorLiteral(EMBEDDING_DIMENSION);

        try {
            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            int inserted = jdbcTemplate.update(
                    "INSERT INTO t_knowledge_vector (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector)",
                    CHUNK_ID, content, metadata, vectorLiteral);
            assertEquals(1, inserted);

            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            List<Map<String, Object>> stored = jdbcTemplate.queryForList(
                    "SELECT content FROM t_knowledge_vector WHERE id = ?", CHUNK_ID);
            assertEquals(1, stored.size());
            assertEquals(content, stored.get(0).get("content"));

            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            List<Map<String, Object>> hits = jdbcTemplate.queryForList(
                    "SELECT id FROM t_knowledge_vector WHERE metadata->>'collection_name' = ? "
                            + "ORDER BY embedding <=> ?::vector LIMIT ?",
                    COLLECTION_NAME, vectorLiteral, 1);
            assertEquals(1, hits.size());
            assertEquals(CHUNK_ID, hits.get(0).get("id"));
        } finally {
            // noinspection SqlDialectInspection,SqlNoDataSourceInspection
            jdbcTemplate.update("DELETE FROM t_knowledge_vector WHERE id = ?", CHUNK_ID);
        }
    }

    private String buildVectorLiteral(int dimension) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < dimension; i++) {
            if (i > 0) {
                literal.append(",");
            }
            literal.append("0.1");
        }
        return literal.append("]").toString();
    }
}

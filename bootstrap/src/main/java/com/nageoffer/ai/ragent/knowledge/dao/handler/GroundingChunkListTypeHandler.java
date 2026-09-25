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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.GroundingChunk;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * PostgreSQL JSONB ↔ {@code List<GroundingChunk>} 类型处理器
 * <p>
 * 推荐问题 grounding 片段随助手消息持久化；读侧遇到非法 JSON 降级为空列表，
 * 避免一行脏数据让整段会话历史加载失败
 */
@Slf4j
@MappedTypes(List.class)
public class GroundingChunkListTypeHandler extends BaseTypeHandler<List<GroundingChunk>> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<GroundingChunk>> LIST_TYPE = new TypeReference<>() {
    };

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<GroundingChunk> parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            PGobject jsonObject = new PGobject();
            jsonObject.setType("jsonb");
            jsonObject.setValue(OBJECT_MAPPER.writeValueAsString(parameter));
            ps.setObject(i, jsonObject);
        } catch (Exception e) {
            throw new SQLException("序列化 grounding 片段失败", e);
        }
    }

    @Override
    public List<GroundingChunk> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<GroundingChunk> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<GroundingChunk> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<GroundingChunk> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<GroundingChunk> value = OBJECT_MAPPER.readValue(raw, LIST_TYPE);
            return value == null ? List.of() : value;
        } catch (Exception e) {
            log.warn("解析 grounding 片段失败，按空列表处理: {}", raw, e);
            return List.of();
        }
    }
}

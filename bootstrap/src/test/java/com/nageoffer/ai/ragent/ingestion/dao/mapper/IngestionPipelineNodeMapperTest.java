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

package com.nageoffer.ai.ragent.ingestion.dao.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 节点整体替换依赖硬删除 SQL 的契约测试
 * <p>
 * 节点表使用 @TableLogic，若回退成 BaseMapper#delete 的逻辑删除，
 * 再次保存相同 node_id 会命中 (pipeline_id, node_id, deleted) 唯一约束
 */
class IngestionPipelineNodeMapperTest {

    @Test
    void physicalDeleteByPipelineIdUsesHardDeleteSql() throws NoSuchMethodException {
        Method method = IngestionPipelineNodeMapper.class.getMethod("physicalDeleteByPipelineId", String.class);

        Delete delete = method.getAnnotation(Delete.class);
        assertNotNull(delete, "必须使用 @Delete 显式声明硬删除 SQL");
        assertEquals("DELETE FROM t_ingestion_pipeline_node WHERE pipeline_id = #{pipelineId}", delete.value()[0]);
        assertEquals(int.class, method.getReturnType());

        Param param = method.getParameters()[0].getAnnotation(Param.class);
        assertNotNull(param);
        assertEquals("pipelineId", param.value());
    }
}

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

package com.nageoffer.ai.ragent.ingestion.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskNodeMapper;
import com.nageoffer.ai.ragent.ingestion.engine.IngestionEngine;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 任务详情 JSON 列解析契约
 * <p>
 * 回归点：原实现把 JSON 字符串交给 {@code BeanUtil.beanToMap}，解析结果是字符串自身的属性表或空对象，
 * 表现为前端「任务元数据 / 节点输出」永远为空
 */
class IngestionTaskServiceImplTest {

    private final IngestionTaskServiceImpl service = new IngestionTaskServiceImpl(
            mock(IngestionEngine.class),
            mock(IngestionPipelineService.class),
            mock(IngestionTaskMapper.class),
            mock(IngestionTaskNodeMapper.class),
            new ObjectMapper());

    @Test
    @DisplayName("合法 JSON 对象解析为对应键值，不再是空对象")
    void parsesJsonObject() throws Exception {
        Map<String, Object> result = readMap("{\"fileName\":\"招生简章.pdf\",\"size\":1024}");

        assertEquals("招生简章.pdf", result.get("fileName"));
        assertEquals(1024, result.get("size"));
    }

    @Test
    @DisplayName("空串、空白与非法 JSON 一律降级为空 Map，不抛异常")
    void degradesInvalidJsonToEmptyMap() throws Exception {
        assertTrue(readMap(null).isEmpty());
        assertTrue(readMap("").isEmpty());
        assertTrue(readMap("   ").isEmpty());
        assertTrue(readMap("{不是合法 JSON").isEmpty());
        assertTrue(readMap("\"纯字符串\"").isEmpty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String raw) throws Exception {
        Method method = IngestionTaskServiceImpl.class.getDeclaredMethod("readMap", String.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(service, raw);
    }
}

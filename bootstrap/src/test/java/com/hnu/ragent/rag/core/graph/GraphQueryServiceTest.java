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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnu.ragent.framework.exception.ServiceException;
import com.hnu.ragent.rag.controller.vo.GraphViewVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 图谱可视化映射：节点 / 边归一、范围过滤、悬空边与截断
 */
class GraphQueryServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("图谱未启用时给出业务异常提示而不是空指针")
    void requiresEnabledBackend() {
        GraphQueryService service = new GraphQueryService(provider(null));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.getGraph("教务处", null, null, 2, 200));
        assertTrue(error.getMessage().contains("rag.graph.type=none"));
        assertThrows(ServiceException.class, () -> service.searchEntities("教务", 20));
    }

    @Test
    @DisplayName("节点名按 entity_id / labels / id 依次回退，<SEP> 多来源串去重归一")
    void mapsNodesAndMergedText() throws Exception {
        GraphQueryService service = new GraphQueryService(provider(null));

        GraphViewVO view = service.mapGraph(root("""
                {
                  "nodes": [
                    {"id":"n1","properties":{"entity_id":"教务处","entity_type":"机构","description":"负责学籍\\u003cSEP\\u003e负责学籍\\u003cSEP\\u003e负责课程"}},
                    {"id":"n2","labels":["学籍科"],"properties":{}},
                    {"id":"n3","properties":{}}
                  ],
                  "edges": [
                    {"id":"e1","source":"n1","target":"n2","properties":{"keywords":"下设\\u003cSEP\\u003e下设\\u003cSEP\\u003e归属","description":"x"}},
                    {"source":"n1","target":"n3","properties":{}}
                  ]
                }
                """), null, 200);

        assertEquals(3, view.getNodes().size());
        assertEquals("教务处", view.getNodes().get(0).getName());
        assertEquals("机构", view.getNodes().get(0).getType());
        assertEquals("负责学籍\n负责课程", view.getNodes().get(0).getDescription());
        assertEquals("学籍科", view.getNodes().get(1).getName());
        assertEquals("n3", view.getNodes().get(2).getName(), "无 entity_id 与 labels 时回退内部 id");

        assertEquals(2, view.getEdges().size());
        assertEquals("下设 / 归属", view.getEdges().get(0).getLabel());
        assertEquals("n1-n3", view.getEdges().get(1).getId(), "缺 id 时用 source-target 兜底");
    }

    @Test
    @DisplayName("按来源过滤节点并丢弃悬空边，超上限截断")
    void filtersByTokenAndTruncates() throws Exception {
        GraphQueryService service = new GraphQueryService(provider(null));
        JsonNode root = root("""
                {
                  "is_truncated": false,
                  "nodes": [
                    {"id":"n1","properties":{"entity_id":"A","file_path":"kb_hr_1001.txt"}},
                    {"id":"n2","properties":{"entity_id":"B","file_path":"kb_other_2002"}},
                    {"id":"n3","properties":{"entity_id":"C","file_path":"kb_hr_1003"}}
                  ],
                  "edges": [
                    {"id":"e1","source":"n1","target":"n2","properties":{}},
                    {"id":"e2","source":"n1","target":"n3","properties":{}}
                  ]
                }
                """);

        GraphViewVO filtered = service.mapGraph(root, "kb_hr_", 200);
        assertEquals(2, filtered.getNodes().size());
        assertEquals(1, filtered.getEdges().size(), "跨到别库的边被剔除");
        assertEquals("e2", filtered.getEdges().get(0).getId());

        GraphViewVO truncated = service.mapGraph(root, "kb_hr_", 1);
        assertEquals(1, truncated.getNodes().size());
        assertTrue(truncated.isTruncated());
        assertTrue(truncated.getEdges().isEmpty(), "截断后两端不全的边也要丢弃");
    }

    private JsonNode root(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private ObjectProvider<LightRagClient> provider(LightRagClient client) {
        @SuppressWarnings("unchecked")
        ObjectProvider<LightRagClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }
}

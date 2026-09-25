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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.controller.vo.GraphViewVO;
import com.nageoffer.ai.ragent.rag.core.graph.GraphQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 知识图谱可视化接口
 * <p>
 * 只读接口：路由始终存在，图谱后端未启用时由服务层返回业务异常提示
 */
@RestController
@RequiredArgsConstructor
public class GraphController {

    private final GraphQueryService graphQueryService;

    /**
     * 查询图谱子图，供后台可视化渲染
     */
    @GetMapping("/graph/view")
    public Result<GraphViewVO> view(@RequestParam(required = false) String entity,
                                    @RequestParam(required = false) String collection,
                                    @RequestParam(required = false) String doc,
                                    @RequestParam(defaultValue = "2") int depth,
                                    @RequestParam(defaultValue = "200") int limit) {
        return Results.success(graphQueryService.getGraph(entity, collection, doc, depth, limit));
    }

    /**
     * 检索实体标签（搜索框用）；keyword 为空时取热门标签
     */
    @GetMapping("/graph/entities")
    public Result<List<String>> entities(@RequestParam(required = false) String keyword,
                                         @RequestParam(defaultValue = "20") int limit) {
        return Results.success(graphQueryService.searchEntities(keyword, limit));
    }
}

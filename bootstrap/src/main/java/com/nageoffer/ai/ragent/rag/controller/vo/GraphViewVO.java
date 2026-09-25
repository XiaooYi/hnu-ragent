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

package com.nageoffer.ai.ragent.rag.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 知识图谱视图对象（后台可视化用）
 * <p>
 * 只承载前端渲染所需的最小字段，图谱侧的 file_path 等内部过滤信息不对外暴露
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphViewVO {

    private List<Node> nodes;

    private List<Edge> edges;

    /**
     * 是否因节点上限被截断
     */
    private boolean truncated;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Node {

        private String id;

        /**
         * 展示名：实体名，缺失时回退内部 id
         */
        private String name;

        /**
         * 实体类型（人物 / 机构 / 课程 等，由图谱侧抽取）
         */
        private String type;

        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Edge {

        private String id;

        private String source;

        private String target;

        /**
         * 关系标签：关键词，缺失时回退关系类型
         */
        private String label;

        private String description;
    }
}

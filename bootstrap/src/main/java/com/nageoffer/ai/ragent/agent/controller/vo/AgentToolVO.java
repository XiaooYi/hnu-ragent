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

package com.nageoffer.ai.ragent.agent.controller.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent 工具视图（管理台用）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentToolVO {

    private String id;

    private String name;

    private String description;

    /**
     * 是否只读；非只读工具执行前需要人工确认
     */
    private boolean readOnly;

    private boolean requiresConfirmation;

    /**
     * LOCAL / MCP
     */
    private String source;

    private List<Parameter> parameters;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Parameter {

        private String name;

        private String type;

        private String description;

        private boolean required;

        private List<String> enumValues;
    }
}

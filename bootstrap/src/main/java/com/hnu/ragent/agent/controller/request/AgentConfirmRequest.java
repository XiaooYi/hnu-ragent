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

package com.hnu.ragent.agent.controller.request;

import lombok.Data;

import java.util.Map;

/**
 * 写操作确认请求
 */
@Data
public class AgentConfirmRequest {

    /**
     * 待执行工具 id（必须在当前工具目录内）
     */
    private String toolId;

    /**
     * 用户确认后的入参（以用户提交为准，可修正模型给错的参数）
     */
    private Map<String, Object> arguments;
}

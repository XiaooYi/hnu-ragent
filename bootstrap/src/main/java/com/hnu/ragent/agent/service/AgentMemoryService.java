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

package com.hnu.ragent.agent.service;

import com.hnu.ragent.agent.dao.entity.AgentMemoryDO;

import java.util.List;

/**
 * Agent 长期记忆
 * <p>
 * 跨会话保留用户事实（偏好 / 背景 / 约束）：回答前按问题召回并注入提示词，回答后从本轮对话沉淀新事实
 */
public interface AgentMemoryService {

    /**
     * 按问题召回当前用户的生效记忆，按相关度排序后取前 N 条
     */
    List<String> recall(String question);

    /**
     * 从一轮对话沉淀记忆；返回新增条数
     * <p>
     * 已存在的相同事实不重复写；带 replaces 的新事实会把被取代的旧记忆置为失效（不物理删除）
     */
    int remember(String question, String answer);

    /**
     * 当前用户的生效记忆（管理/展示用）
     */
    List<AgentMemoryDO> listActive();

    /**
     * 手工让一条记忆失效（用户说「忘了这件事」）
     */
    void invalidate(String memoryId);
}

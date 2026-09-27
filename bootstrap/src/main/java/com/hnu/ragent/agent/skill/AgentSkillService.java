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

package com.hnu.ragent.agent.skill;

import java.util.List;

/**
 * 技能手册服务
 */
public interface AgentSkillService {

    /**
     * 已加载的全部技能
     */
    List<AgentSkill> listSkills();

    /**
     * 按问题匹配技能：触发词命中即入选，最多返回 limit 条
     */
    List<AgentSkill> match(String question, int limit);
}

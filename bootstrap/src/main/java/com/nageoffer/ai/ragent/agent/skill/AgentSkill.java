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

package com.nageoffer.ai.ragent.agent.skill;

import java.util.List;

/**
 * 技能手册
 * <p>
 * 技能是「某类任务的固定做法」：触发词命中时把手册正文注入提示词，让模型按既定步骤做事，
 * 而不是每次都靠自由发挥
 *
 * @param name        技能名
 * @param description 一句话说明（给模型与前端看）
 * @param triggers    触发词
 * @param content     手册正文
 */
public record AgentSkill(String name, String description, List<String> triggers, String content) {

    public AgentSkill {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
    }

    /**
     * 渲染成注入提示词的一段文本
     */
    public String render() {
        return "【技能：" + name + "】" + description + "\n" + content;
    }
}

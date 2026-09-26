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

package com.nageoffer.ai.ragent.agent.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * Agent 长期记忆事实
 * <p>
 * 事实不物理删除：被新事实取代时写 {@code invalidAt} 与 {@code supersededBy}，读路径只取生效中的行
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_agent_memory")
public class AgentMemoryDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String userId;

    /**
     * 一句话事实
     */
    private String content;

    /**
     * PREFERENCE / FACT / CONTEXT
     */
    private String sourceType;

    /**
     * 失效时间；null 表示生效中
     */
    private Date invalidAt;

    /**
     * 取代该条记忆的新记忆 ID
     */
    private String supersededBy;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}

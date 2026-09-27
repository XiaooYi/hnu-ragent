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

package com.hnu.ragent.audit.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hnu.ragent.knowledge.dao.handler.JsonbTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 业务数据变更审计日志实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "t_biz_change_log", autoResultMap = true)
public class BizChangeLogDO {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 业务对象类型
     */
    private String bizType;

    /**
     * 业务对象主键
     */
    private String bizId;

    /**
     * 操作类型
     */
    private String operationType;

    /**
     * 操作描述
     */
    private String actionDesc;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String beforeSnapshot;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String afterSnapshot;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String changeDiff;

    private String operatorId;

    private String operatorName;

    private String operatorRole;

    /**
     * 是否成功
     */
    private Boolean success;

    /**
     * 失败信息
     */
    private String errorMessage;

    /**
     * 触发类名
     */
    private String className;

    /**
     * 触发方法名
     */
    private String methodName;

    /**
     * 来源 IP
     */
    private String ip;

    private String userAgent;

    @TableField(fill = FieldFill.INSERT)
    private Date createTime;
}

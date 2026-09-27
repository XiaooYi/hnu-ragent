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

package com.hnu.ragent.audit.controller.request;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.util.Date;

/**
 * 业务变更审计日志分页查询请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BizChangeLogPageRequest extends Page {

    /**
     * 业务对象类型
     */
    private String bizType;

    /**
     * 业务对象主键（模糊匹配）
     */
    private String bizId;

    /**
     * 操作类型
     */
    private String operationType;

    private String operatorId;

    /**
     * 操作人名称（模糊匹配）
     */
    private String operatorName;

    /**
     * 是否成功
     */
    private Boolean success;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date beginTime;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;
}

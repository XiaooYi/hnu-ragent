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

package com.nageoffer.ai.ragent.audit.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 业务变更审计埋点
 * <p>
 * 标注在写操作方法上，方法结束后由 {@code BizChangeLogAspect} 落一条审计记录。
 * 三个表达式字段都支持 {@code {{表达式}}} 模板（Spring SpEL + 方法参数名），例如
 * {@code "#requestParam.id"}、{@code "{{#requestParam.name}}"}。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BizChangeLog {

    /**
     * 业务对象类型，取值见 {@code BizChangeBizType}
     */
    String bizType();

    /**
     * 操作类型，取值见 {@code BizChangeOperationType}；支持 {@code {{表达式}}} 模板，
     * 便于同一方法按入参区分（如 {@code "{{#enabled ? 'ENABLE' : 'DISABLE'}}"}）
     */
    String operationType();

    /**
     * 成功时的操作描述模板
     */
    String success();

    /**
     * 失败时的操作描述模板，异常信息通过 {@code #_errorMsg} 引用
     */
    String fail() default "";

    /**
     * 业务主键表达式；留空时取上下文快照里的 bizId，仍取不到记为 UNKNOWN
     */
    String bizId() default "";
}

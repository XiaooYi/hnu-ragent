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

package com.nageoffer.ai.ragent.rag.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 意图层级枚举
 * 用于表示知识库中意图的层级结构
 */
@Getter
@RequiredArgsConstructor
public enum IntentLevel {

    /**
     * 顶层：湖大通用概况与校园生活 / 湖大本科教学与学业制度 / 湖大研究生新生与研究生管理
     */
    DOMAIN(0),

    /**
     * 第二层：湖大通用概况与校园生活主题 / 湖大本科教学与学业制度主题 ...
     */
    CATEGORY(1),

    /**
     * 第三层：更具体的 Topic，如 系统介绍 / 数据安全 / 架构设计
     */
    TOPIC(2);

    private final int code;

    /**
     * 根据编码获取对应的意图层级
     *
     * @param code 层级编码
     * @return 对应的IntentLevel枚举值，如果code为null或不存在则返回null
     */
    public static IntentLevel fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (IntentLevel e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }

    /**
     * 返回枚举的名称
     *
     * @return 枚举名称字符串
     */
    @Override
    public String toString() {
        return name();
    }
}

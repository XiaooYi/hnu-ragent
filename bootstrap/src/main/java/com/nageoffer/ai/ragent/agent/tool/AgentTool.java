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

package com.nageoffer.ai.ragent.agent.tool;

import java.util.List;
import java.util.Map;

/**
 * Agent 本地工具
 * <p>
 * 实现类标注 {@code @Component} 即被 {@link AgentToolCatalog} 自动发现。
 * {@link #readOnly()} 返回 false 的工具属于写操作，必须等 P3 的确认流程就绪后才允许注册到目录
 */
public interface AgentTool {

    /**
     * 工具 ID（模型用它选择工具，全局唯一）
     */
    String id();

    /**
     * 展示名
     */
    String name();

    /**
     * 工具用途说明（模型据此判断该不该用这个工具）
     */
    String description();

    /**
     * 参数描述；无参数返回空列表
     */
    List<AgentToolParameter> parameters();

    /**
     * 是否只读。默认 true：写操作必须显式声明，避免误注册
     */
    default boolean readOnly() {
        return true;
    }

    /**
     * 执行工具，返回给模型看的观察结果文本
     */
    String execute(Map<String, Object> arguments);
}

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

package com.nageoffer.ai.ragent.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 运行时配置
 * <p>
 * enabled 默认 false：不注册引擎实现 bean，RAG 链路行为与「未引入 Agent」完全一致。
 * 写操作工具必须等 P3 的确认流程（默认拒绝 + 显式确认）就绪后才允许注册
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.agent")
public class AgentProperties {

    /**
     * 是否启用 Agent 运行时
     */
    private Boolean enabled = false;

    /**
     * 单轮最多几次「想 + 调工具」；达到上限后用最后一次观察收口，不无限循环
     */
    private Integer maxSteps = 6;

    /**
     * 模型温度：工具选择需要稳定，默认取保守值
     */
    private Double temperature = 0.2D;

    /**
     * topP
     */
    private Double topP = 0.3D;

    /**
     * 工具白名单：为空表示目录内全部可用；填写后只暴露这些工具
     */
    private List<String> allowedTools = new ArrayList<>();

    /**
     * 单次工具调用的结果最大字符数，超出截断，避免观察结果把上下文撑爆
     */
    private Integer maxObservationChars = 4000;

    /**
     * 长期记忆
     */
    private Memory memory = new Memory();

    /**
     * 技能手册
     */
    private Skills skills = new Skills();

    @Data
    public static class Memory {

        /**
         * 是否启用长期记忆（抽取 + 召回）
         * <p>
         * 默认关闭：抽取要额外调一次模型，开启前先确认收益（记忆写错比不写更糟）
         */
        private Boolean enabled = false;

        /**
         * 单轮最多注入几条记忆
         */
        private Integer recallLimit = 5;

        /**
         * 单轮对话最多沉淀几条新事实
         */
        private Integer maxFactsPerTurn = 3;
    }

    @Data
    public static class Skills {

        /**
         * 是否启用技能手册注入
         * <p>
         * 默认开启：技能手册是随代码发布的静态文本，不产生额外模型调用；关闭时只影响提示词内容
         */
        private Boolean enabled = true;

        /**
         * 单轮最多注入几份手册
         */
        private Integer maxSkills = 2;
    }
}

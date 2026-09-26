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

package com.nageoffer.ai.ragent.initializer;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 场景初始化器配置
 * <p>
 * 默认关闭：初始化会写业务表，只能由人显式打开一次，不能随应用启动自动执行
 */
@Data
@Component
@ConfigurationProperties(prefix = "initializer")
public class InitializerProperties {

    /**
     * 是否启用初始化（默认 false）
     */
    private Boolean enabled = false;

    /**
     * 数据集名：对应 {@code resources/initializer/<name>/}
     */
    private String scenario = "hnu";

    /**
     * 执行模式：seed（灌数据）/ cleanup（清理该数据集写入的数据）
     */
    private String mode = "seed";

    /**
     * 是否在任一步失败时中断（默认 true，配置错误应尽早暴露）
     */
    private Boolean failFast = true;
}

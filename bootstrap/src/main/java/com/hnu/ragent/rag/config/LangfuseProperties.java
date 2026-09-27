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

package com.hnu.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LangFuse 上报配置
 * <p>
 * 默认关闭：本地已有 {@code t_rag_trace_run/node}（含后端查询页），LangFuse 的价值在跨服务可视化，
 * 没有对应平台时不应产生任何网络请求
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.trace.langfuse")
public class LangfuseProperties {

    /**
     * 是否上报
     */
    private Boolean enabled = false;

    /**
     * LangFuse 服务地址，如 https://cloud.langfuse.com
     */
    private String host = "";

    private String publicKey = "";

    private String secretKey = "";

    /**
     * 环境名（LangFuse 的 environment 维度），便于区分本地 / 测试 / 生产
     */
    private String environment = "default";

    /**
     * 上报超时（毫秒）：上报失败不影响业务，超时设短些
     */
    private Integer timeoutMs = 3000;

    /**
     * 是否上报 Agent 运行（含工具步骤）
     */
    private Boolean agentEnabled = true;

    /**
     * 配置是否可用（地址与密钥齐全）
     */
    public boolean usable() {
        return Boolean.TRUE.equals(enabled)
                && host != null && !host.isBlank()
                && publicKey != null && !publicKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }
}

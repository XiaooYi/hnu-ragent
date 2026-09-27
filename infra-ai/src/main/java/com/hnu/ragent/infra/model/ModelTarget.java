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

package com.hnu.ragent.infra.model;

import com.hnu.ragent.infra.config.AIModelProperties;

/**
 * 模型目标配置记录
 * <p>
 * 用于封装 AI 模型的配置信息，包括模型标识、候选模型配置、提供商配置与档位超时预算
 *
 * @param id        模型唯一标识符
 * @param candidate 模型候选配置，包含模型的具体参数和设置
 * @param provider  提供商配置，包含模型提供商的相关信息
 * @param timeoutMs 档位超时预算（毫秒）：流式下是首包 TTFT 预算，同步下是整段调用上限；
 *                  embedding / rerank / vlm 无档位预算，为 {@code null} 时走 HTTP 客户端默认
 */
public record ModelTarget(
        String id,
        AIModelProperties.ModelCandidate candidate,
        AIModelProperties.ProviderConfig provider,
        Long timeoutMs
) {

    /**
     * 无档位预算的兼容构造（embedding / rerank / vlm 及既有调用方）
     */
    public ModelTarget(String id,
                       AIModelProperties.ModelCandidate candidate,
                       AIModelProperties.ProviderConfig provider) {
        this(id, candidate, provider, null);
    }
}

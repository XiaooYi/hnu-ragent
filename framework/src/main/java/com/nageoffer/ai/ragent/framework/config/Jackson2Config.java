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

package com.nageoffer.ai.ragent.framework.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 显式提供 Jackson 2 的 {@link ObjectMapper}
 * <p>
 * Spring Boot 4 的默认 JSON 实现换成 Jackson 3（{@code tools.jackson}），容器里不再有
 * {@code com.fasterxml.jackson.databind.ObjectMapper} bean；而本项目大量既有代码（意图缓存、审计快照、
 * 图谱客户端、记忆抽取解析等）仍基于 Jackson 2，因此在这里补一个 bean，避免为「升级容器」重写所有 JSON 处理。
 * <p>
 * 迁移到 Jackson 3 属于独立议题（要评估注解包名、Feature 开关与各客户端兼容性），在此之前保持 Jackson 2 行为。
 * <p>
 * 默认值与 Boot 的惯例对齐：关闭「未知字段报错」，否则读取历史缓存/外部响应时容易被新增字段打断。
 */
@Configuration
public class Jackson2Config {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper jackson2ObjectMapper() {
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
}

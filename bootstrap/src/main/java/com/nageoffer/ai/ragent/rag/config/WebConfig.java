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

package com.nageoffer.ai.ragent.rag.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Web MVC 配置
 *
 * <p>
 * 主要用于统一配置 Spring MVC 的消息转换器，确保字符串响应使用 UTF-8 编码，避免出现中文乱码或不同编码混用的问题
 * </p>
 *
 * <p>
 * 默认情况下，Spring Boot 会自动配置一组 {@link HttpMessageConverter}，
 * 其中 {@link StringHttpMessageConverter} 的编码可能不是 UTF-8，通过此配置可以显式设置为 UTF-8 并放到转换器链的最前面
 * </p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 自定义消息转换器配置
     *
     * <p>
     * 这里通过把默认的 {@link StringHttpMessageConverter} 就地换成 UTF-8 版本，
     * 来覆盖默认的 String 类型消息转换行为
     * </p>
     *
     * <p>
     * 必须用 {@code extendMessageConverters} 而不是 {@code configureMessageConverters}：
     * Spring MVC 组装转换器链的顺序是「配置器填充 → 列表仍为空才注册默认转换器 → 扩展」，
     * 只要在 {@code configureMessageConverters} 里放入任何转换器，JSON 等默认转换器就再也不会被注册，
     * 所有返回对象的接口都会 406（No acceptable representation）。
     * </p>
     *
     * @param converters Spring MVC 已注册的消息转换器列表（含默认转换器）
     */
    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        StringHttpMessageConverter utf8Converter = utf8StringConverter();
        for (int i = 0; i < converters.size(); i++) {
            // 就地替换：保持原有位置与其它转换器不变，只把编码换成 UTF-8
            if (converters.get(i) instanceof StringHttpMessageConverter) {
                converters.set(i, utf8Converter);
                return;
            }
        }
        converters.add(0, utf8Converter);
    }

    /**
     * 使用 UTF-8 作为字符串响应的默认编码
     * <p>
     * 同时关闭响应头里的 accept-charset 列表，避免某些客户端或中间件对该头部解析不兼容
     */
    private StringHttpMessageConverter utf8StringConverter() {
        StringHttpMessageConverter converter = new StringHttpMessageConverter(StandardCharsets.UTF_8);
        converter.setWriteAcceptCharset(false);
        return converter;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }
}

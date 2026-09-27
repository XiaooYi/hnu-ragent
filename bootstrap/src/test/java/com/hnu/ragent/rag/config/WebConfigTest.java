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

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消息转换器链的装配契约
 * <p>
 * Spring MVC 的 {@code WebMvcConfigurationSupport#getMessageConverters()} 只会这样组装一次：
 * 先调用所有配置器的 {@code configureMessageConverters(list)}，**只有当 list 仍为空时**
 * 才调用 {@code addDefaultHttpMessageConverters(list)}，最后调用 {@code extendMessageConverters(list)}。
 * <p>
 * 也就是说：在 {@code configureMessageConverters} 里放任何东西，都会让 JSON 等默认转换器**整体失效**，
 * 表现是所有返回对象的接口都 406（No acceptable representation）。因此这里只允许用
 * {@code extendMessageConverters} 做"就地替换/追加"。
 */
class WebConfigTest {

    private final WebConfig webConfig = new WebConfig();

    @Test
    void doesNotPopulateConfigureMessageConvertersSoSpringMvcStillRegistersDefaults() {
        List<HttpMessageConverter<?>> converters = new ArrayList<>();

        webConfig.configureMessageConverters(converters);

        // 只要这里非空，Spring MVC 就会跳过默认转换器注册（含 JSON），线上表现为全站 406
        assertThat(converters).isEmpty();
    }

    @Test
    void extendMessageConvertersSwapsDefaultStringConverterForUtf8OneInPlace() {
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        StringHttpMessageConverter defaultStringConverter = new StringHttpMessageConverter();
        MappingJackson2HttpMessageConverter jsonConverter = new MappingJackson2HttpMessageConverter();
        converters.add(defaultStringConverter);
        converters.add(jsonConverter);

        webConfig.extendMessageConverters(converters);

        assertThat(converters).hasSize(2);
        assertThat(converters.get(1)).isSameAs(jsonConverter);
        assertThat(converters.get(0)).isInstanceOf(StringHttpMessageConverter.class);
        assertThat(converters.get(0)).isNotSameAs(defaultStringConverter);
        assertThat(((StringHttpMessageConverter) converters.get(0)).getDefaultCharset())
                .isEqualTo(StandardCharsets.UTF_8);
    }

    @Test
    void extendMessageConvertersPrependsUtf8StringConverterWhenNonePresent() {
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        MappingJackson2HttpMessageConverter jsonConverter = new MappingJackson2HttpMessageConverter();
        converters.add(jsonConverter);

        webConfig.extendMessageConverters(converters);

        assertThat(converters).hasSize(2);
        assertThat(converters.get(0)).isInstanceOf(StringHttpMessageConverter.class);
        assertThat(((StringHttpMessageConverter) converters.get(0)).getDefaultCharset())
                .isEqualTo(StandardCharsets.UTF_8);
        assertThat(converters.get(1)).isSameAs(jsonConverter);
    }
}

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

import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 从 classpath 加载初始化数据集
 * <p>
 * 目录约定：{@code initializer/<scenario>/scenario.properties} + {@code sample-questions.txt} +
 * {@code intents.tsv}
 */
@Component
@RequiredArgsConstructor
public class InitializerDatasetLoader {

    private static final String BASE = "classpath:initializer/";

    private final ResourceLoader resourceLoader;

    public InitializerDataset load(String scenario) {
        Properties properties = new Properties();
        try (InputStream input = resource(scenario, "scenario.properties").getInputStream()) {
            properties.load(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("初始化数据集不存在或不可读: " + scenario, e);
        }
        return InitializerDataset.fromProperties(properties,
                InitializerDataset.parseSampleQuestions(readIfExists(scenario, "sample-questions.txt")),
                readIfExists(scenario, "intents.tsv"));
    }

    private String readIfExists(String scenario, String fileName) {
        Resource resource = resource(scenario, fileName);
        if (!resource.exists()) {
            return "";
        }
        try (InputStream input = resource.getInputStream()) {
            return StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取初始化数据集文件失败: " + fileName, e);
        }
    }

    private Resource resource(String scenario, String fileName) {
        return resourceLoader.getResource(BASE + scenario + "/" + fileName);
    }
}

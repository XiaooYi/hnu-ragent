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

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 初始化过程记录
 * <p>
 * 逐条打印 + 末尾汇总：初始化失败后要能一眼看出「已经灌了什么、从哪一步断了」，
 * 否则重跑时会分不清是自己的数据还是初始化器的残留
 */
@Slf4j
public class InitializerSummary {

    private final List<String> created = new ArrayList<>();
    private final List<String> skipped = new ArrayList<>();
    private final List<String> deleted = new ArrayList<>();
    private final List<String> failed = new ArrayList<>();

    public void created(String item) {
        created.add(item);
        log.info("[初始化] 新增 {}", item);
    }

    public void skipped(String item) {
        skipped.add(item);
        log.info("[初始化] 跳过 {}", item);
    }

    public void deleted(String item) {
        deleted.add(item);
        log.info("[初始化] 删除 {}", item);
    }

    public void failed(String item) {
        failed.add(item);
        log.warn("[初始化] 失败 {}", item);
    }

    public List<String> created() {
        return List.copyOf(created);
    }

    public List<String> skipped() {
        return List.copyOf(skipped);
    }

    public List<String> deleted() {
        return List.copyOf(deleted);
    }

    public List<String> failed() {
        return List.copyOf(failed);
    }

    public String render() {
        return String.format("新增 %d 项 / 跳过 %d 项 / 删除 %d 项 / 失败 %d 项",
                created.size(), skipped.size(), deleted.size(), failed.size());
    }
}

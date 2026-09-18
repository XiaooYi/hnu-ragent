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

package com.nageoffer.ai.ragent.rag.controller.request;

import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import lombok.Data;

import java.util.List;

/**
 * 检索调试请求
 * <p>
 * 对单条 query 单独执行检索链路并查看各阶段中间结果，
 * 用于排查召回质量、调参对比、验证通道启用逻辑等场景
 */
@Data
public class RetrieveDebugRequest {

    /**
     * 检索问题（必填）
     */
    private String question;

    /**
     * 最终返回的 TopK，默认取配置 {@code rag.search.default-top-k}（10）
     */
    private Integer topK;

    /**
     * 强制启用的通道白名单。
     * 不传则走通道自身的 {@code isEnabled()} 逻辑；
     * 传入时只启用列表中指定的通道，跳过其他通道。
     */
    private List<SearchChannelType> enableChannels;

    /**
     * 是否启用 Rerank 重排序，默认 {@code true}
     */
    private Boolean enableRerank;

    /**
     * 是否启用去重，默认 {@code true}
     */
    private Boolean enableDedup;

    /**
     * 限定检索的 collection 名称列表。
     * 不传则走正常逻辑（意图定向取意图对应 collection，全局检索取全量 KB collection）
     */
    private List<String> collectionNames;

    /**
     * 覆盖全局检索的意图置信度阈值，方便调参对比。
     * 不传则使用配置 {@code rag.search.channels.vector-global.confidence-threshold}（默认 0.6）
     */
    private Double overrideConfidenceThreshold;

    /**
     * 调试模式。
     * {@code true} 时返回每个 chunk 的完整文本（textFull），方便排查内容截断问题。
     * 默认 {@code false}，仅返回截断后的文本。
     */
    private Boolean debugMode;
}

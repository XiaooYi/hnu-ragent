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

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 检索配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.search")
public class SearchChannelProperties implements InitializingBean {

    /**
     * 默认返回的 TopK
     */
    private int defaultTopK = 10;

    /**
     * 检索通道配置
     */
    private Channels channels = new Channels();

    @Data
    public static class Channels {

        /**
         * 单通道超时上限（毫秒）
         * <p>
         * 通道并行执行，整次检索耗时被钳制在最慢一条上：任一通道后端劣化（ES 慢查询、图谱服务超时、向量库抖动），
         * 整个子问题都要等它。超过此值的通道按空结果降级、其余通道照常融合；<=0 不限时，退回等最慢通道
         */
        private long timeoutMs = 15_000;

        /**
         * 向量全局检索配置
         */
        private VectorGlobal vectorGlobal = new VectorGlobal();

        /**
         * 意图定向检索配置
         */
        private IntentDirected intentDirected = new IntentDirected();

        /**
         * 关键词检索配置（仅当 rag.keyword.type=es 时该通道才存在）
         */
        private Keyword keyword = new Keyword();

        /**
         * 联网检索配置（You.com Search）
         */
        private WebSearch webSearch = new WebSearch();

        /**
         * 图谱检索配置（仅当 rag.graph.type=lightrag 时该通道才存在）
         */
        private Graph graph = new Graph();
    }

    /**
     * 多通道结果融合配置
     */
    private Fusion fusion = new Fusion();

    /**
     * 证据相关性闸门
     * 判定这批证据够不够格进提示词，与「查哪些库」「用什么模态」无关，故与 fusion 平级
     */
    private Evidence evidence = new Evidence();

    /**
     * 检索作用域与预算
     */
    private Scope scope = new Scope();

    @Override
    public void afterPropertiesSet() {
        // 漏斗不变式：通道取数深度小于最终条数时必然拿不满，属于配置矛盾
        int contextTopK = Math.max(1, defaultTopK);
        int recallBudget = scope.resolveRecallBudget(fusion.getRerankCandidateLimit());
        if (recallBudget > 0 && recallBudget < contextTopK) {
            throw new IllegalStateException(String.format(
                    "检索预算漏斗不变式被破坏：recall-budget(%d) < default-top-k(%d)，"
                            + "通道取数深度不得小于最终条数；请调大 rag.search.scope.recall-budget 或调小 rag.search.default-top-k",
                    recallBudget, contextTopK));
        }

        // 精排分按 0~1 输出，下限高于 1 则全部证据被丢，表现与「库里没料」一致，线上无从分辨
        double minRerankScore = evidence.getMinRerankScore();
        if (Double.isNaN(minRerankScore) || minRerankScore > 1) {
            throw new IllegalStateException(String.format(
                    "rag.search.evidence.min-rerank-score(%s) 必须 <=1：精排分按 0~1 输出，"
                            + "高于 1 会让全部证据被闸门丢弃、知识库侧恒为空；关闭闸门请填 0",
                    minRerankScore));
        }
    }

    /**
     * 证据相关性闸门
     * 检索只保证返回最像的 N 条，库里没答案时照样满额返回，闸门补一道相关度下限
     */
    @Data
    public static class Evidence {

        /**
         * 最低精排分 0~1
         * <p>
         * 整批最高分低于此值则整批丢弃，判为「没检索到相关内容」。
         * 与意图分（作用域收窄）不是一套量纲，刻意分开配置。
         * <=0 关闭；无分可读时（精排关闭或降级 noop）放行。
         */
        private double minRerankScore = 0.2;
    }

    /**
     * 检索作用域与预算
     */
    @Data
    public static class Scope {

        /**
         * 召回预算：各通道的取数深度（候选池大小）
         * <p>
         * 与 {@link SearchChannelProperties#defaultTopK}（最终进入上下文的条数）是两件事，
         * 通道不再各自乘倍数。{@code <=0} 时回退到 {@code rag.search.fusion.rerank-candidate-limit}——
         * 召回超过候选池上限的部分下游必被截断、属空转，故默认跟随候选池上限（单一真源）
         */
        private int recallBudget = 0;

        /**
         * 解析召回预算：显式配置优先，未配置时跟随 Rerank 候选池上限
         */
        public int resolveRecallBudget(int candidateLimitFallback) {
            return recallBudget > 0 ? recallBudget : candidateLimitFallback;
        }
    }

    @Data
    public static class WebSearch {

        /**
         * 是否启用
         * <p>
         * 默认关闭。开启后还需能解析到 API Key（配置 {@code api-key}，为空回退环境变量
         * {@code YDC_API_KEY}），缺一通道不生效
         */
        private boolean enabled = false;

        /**
         * 最多返回的结果条数（网页 + 新闻合计）
         * <p>
         * 默认 5，上限 20；向 You.com 传的是「每 section」数量，合并后由通道统一截断到此值
         */
        private int count = 5;

        /**
         * 请求超时（秒）
         */
        private int timeoutSeconds = 10;

        /**
         * You.com Search API Key
         * <p>
         * 建议留空，此时回退读取环境变量 {@code YDC_API_KEY}，避免密钥落入配置文件
         */
        private String apiKey = "";

        /**
         * You.com Search API 地址
         * <p>
         * 一般无需修改，测试时可指向本地 stub
         */
        private String apiUrl = "https://ydc-index.io/v1/search";
    }

    @Data
    public static class Graph {

        /**
         * 是否启用图谱检索通道
         * 默认关闭：rag.graph.type 默认 none，置 true 会与「后端未装配」矛盾并被启动校验拦截
         */
        private boolean enabled = false;

        /**
         * 检索范围模式：global（全库）/ intent（仅意图域）/ both（有意图走意图域，否则全库）
         */
        private String mode = "both";
    }

    @Data
    public static class Fusion {

        /**
         * 融合策略，当前支持 rrf（倒数名次融合）；其它取值等价于关闭融合
         */
        private String strategy = "rrf";

        /**
         * RRF 平滑常数 k，经验值 60
         */
        private int rrfK = 60;

        /**
         * RRF 融合后送入 Rerank 的候选上限，<=0 表示不截断（经验值 40~100）
         */
        private int rerankCandidateLimit = 50;
    }

    @Data
    public static class VectorGlobal {

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 意图置信度阈值
         * 当意图识别的最高分数低于此阈值时，启用全局检索
         */
        private double confidenceThreshold = 0.6;

        /**
         * 单意图补充检索阈值
         * 当仅识别出一个意图且分数低于此阈值时，启用全局检索作为安全网
         */
        private double singleIntentSupplementThreshold = 0.8;

    }

    @Data
    public static class IntentDirected {

        /**
         * 是否启用
         */
        private boolean enabled = true;

        /**
         * 最低意图分数
         * 低于此分数的意图节点会被过滤
         */
        private double minIntentScore = 0.4;

    }

    @Data
    public static class Keyword {

        /**
         * 是否启用关键词检索通道
         * 默认关闭：rag.keyword.type 默认 none，置 true 会与「后端未装配」矛盾并被启动校验拦截
         */
        private boolean enabled = false;

        /**
         * 检索范围模式：global（全库）/ intent（仅意图域）/ both（有意图走意图域，否则全库）
         */
        private String mode = "both";

    }
}

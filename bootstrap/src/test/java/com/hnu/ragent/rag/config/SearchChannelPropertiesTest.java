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
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SearchChannelPropertiesTest {

    @Test
    void minRerankScoreAboveOneThrows() {
        SearchChannelProperties props = new SearchChannelProperties();
        props.getEvidence().setMinRerankScore(1.5);

        assertThrows(IllegalStateException.class, props::afterPropertiesSet,
                "精排分按 0~1 输出，下限高于 1 会让知识库侧永久静默全空，且表现与「库里没料」无从分辨");

        props.getEvidence().setMinRerankScore(0);
        assertDoesNotThrow(props::afterPropertiesSet, "置零是关闭闸门的回退路径，不应拦截");
    }

    @Test
    void defaultsAreValidAndDocumented() {
        SearchChannelProperties props = new SearchChannelProperties();

        assertDoesNotThrow(props::afterPropertiesSet);
        assertEquals(0.2, props.getEvidence().getMinRerankScore());
        assertEquals(60, props.getFusion().getRrfK());
        assertEquals("both", props.getChannels().getKeyword().getMode());
        // 默认关闭关键词通道：rag.keyword.type 默认 none，开启会与后端未装配矛盾
        assertEquals(false, props.getChannels().getKeyword().isEnabled());
    }

    @Test
    @DisplayName("召回预算小于最终条数时启动即失败（漏斗不变式）")
    void recallBudgetBelowContextTopKThrows() {
        SearchChannelProperties props = new SearchChannelProperties();
        props.getScope().setRecallBudget(5);

        assertThrows(IllegalStateException.class, props::afterPropertiesSet,
                "取数深度小于最终条数必然拿不满，应在启动期拦截");
    }

    @Test
    @DisplayName("召回预算未配置时跟随 Rerank 候选池上限，且等于最终条数合法")
    void recallBudgetFallsBackToCandidateLimit() {
        SearchChannelProperties fallback = new SearchChannelProperties();
        assertDoesNotThrow(fallback::afterPropertiesSet);
        assertEquals(fallback.getFusion().getRerankCandidateLimit(),
                fallback.getScope().resolveRecallBudget(fallback.getFusion().getRerankCandidateLimit()));

        SearchChannelProperties equal = new SearchChannelProperties();
        equal.getScope().setRecallBudget(10);
        assertDoesNotThrow(equal::afterPropertiesSet, "recall-budget 等于 default-top-k 合法");
    }
}

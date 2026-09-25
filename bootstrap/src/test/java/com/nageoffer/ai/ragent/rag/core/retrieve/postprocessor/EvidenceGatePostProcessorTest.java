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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 证据相关性闸门的放行 / 丢弃判定
 * <p>
 * 每个用例的 {@code score} 与 {@code rerankScore} 都设成分叉值，实现若退回读 {@code getScore()} 必须变红
 */
class EvidenceGatePostProcessorTest {

    private static final float RRF_SCORE = 0.03f;

    @Test
    void dropsWholeBatchWhenTopRerankScoreBelowFloor() {
        EvidenceGatePostProcessor gate = gateWithFloor(0.2);
        List<RetrievedChunk> chunks = List.of(
                chunk("a", RRF_SCORE, 0.05f),
                chunk("b", RRF_SCORE, 0.01f));

        assertTrue(gate.process(chunks, List.of(), context()).isEmpty());
    }

    @Test
    void keepsWholeBatchWhenTopRerankScoreReachesFloor() {
        EvidenceGatePostProcessor gate = gateWithFloor(0.2);
        List<RetrievedChunk> chunks = List.of(
                chunk("a", RRF_SCORE, 0.55f),
                chunk("b", RRF_SCORE, 0.01f));

        List<RetrievedChunk> result = gate.process(chunks, List.of(), context());

        assertEquals(2, result.size(), "批级放行后弱证据一并保留，不做逐条过滤");
    }

    @Test
    void passesThroughWhenNoRerankScoreAvailable() {
        EvidenceGatePostProcessor gate = gateWithFloor(0.99);
        List<RetrievedChunk> chunks = List.of(chunk("a", RRF_SCORE, null));

        assertEquals(1, gate.process(chunks, List.of(), context()).size(),
                "精排降级 noop 时无分可读，闸门必须放行而不是把知识库侧整条关掉");
    }

    @Test
    void disabledWhenFloorIsZero() {
        EvidenceGatePostProcessor gate = gateWithFloor(0);

        assertFalse(gate.isEnabled(context()));
        assertEquals(15, gate.getOrder());
    }

    @Test
    void failsFastWhenGateEnabledButRerankDisabled() {
        assertThrows(IllegalStateException.class, () -> gateWithFloor(0.2, false));
    }

    @Test
    void allowingGateWithRerankDisabledIsValidWhenFloorIsZero() {
        EvidenceGatePostProcessor gate = gateWithFloor(0, false);

        assertFalse(gate.isEnabled(context()));
    }

    private static EvidenceGatePostProcessor gateWithFloor(double minRerankScore) {
        return gateWithFloor(minRerankScore, true);
    }

    private static EvidenceGatePostProcessor gateWithFloor(double minRerankScore, boolean rerankEnabled) {
        SearchChannelProperties props = new SearchChannelProperties();
        props.getEvidence().setMinRerankScore(minRerankScore);
        RAGConfigProperties ragConfig = new RAGConfigProperties();
        ragConfig.setRerankEnabled(rerankEnabled);
        EvidenceGatePostProcessor gate = new EvidenceGatePostProcessor(props, ragConfig);
        gate.afterPropertiesSet();
        return gate;
    }

    private static RetrievedChunk chunk(String id, Float score, Float rerankScore) {
        return RetrievedChunk.builder().id(id).text(id).score(score).rerankScore(rerankScore).build();
    }

    private static SearchContext context() {
        return SearchContext.builder().originalQuestion("转专业需要什么条件").topK(10).build();
    }
}

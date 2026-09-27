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

package com.hnu.ragent.rag.core.retrieve.channel;

import com.hnu.ragent.framework.convention.RetrievedChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通道出口统一排序：分数降序、空分沉底、同分按 id 稳定
 */
class ChunkRankingTest {

    @Test
    @DisplayName("分数降序，缺失分数沉底，同分按 chunk id 稳定")
    void sortsByScoreDescendingWithStableTieBreak() {
        List<RetrievedChunk> sorted = ChunkRanking.sortedByScore(List.of(
                chunk("b", 0.5F),
                chunk("c", null),
                chunk("a", 0.9F),
                chunk("d", 0.5F)));

        assertEquals(List.of("a", "b", "d", "c"), sorted.stream().map(RetrievedChunk::getId).toList());
    }

    @Test
    @DisplayName("排序不修改入参列表")
    void sortingDoesNotMutateInput() {
        List<RetrievedChunk> input = new java.util.ArrayList<>(List.of(chunk("b", 0.1F), chunk("a", 0.9F)));

        ChunkRanking.sortedByScore(input);

        assertEquals(List.of("b", "a"), input.stream().map(RetrievedChunk::getId).toList());
    }

    @Test
    @DisplayName("截断：limit<=0 或超出长度时返回等值副本")
    void capsAsExpected() {
        List<RetrievedChunk> chunks = List.of(chunk("a", 0.9F), chunk("b", 0.8F), chunk("c", 0.7F));

        assertEquals(3, ChunkRanking.cap(chunks, 0).size());
        assertEquals(3, ChunkRanking.cap(chunks, 10).size());
        assertEquals(List.of("a", "b"), ChunkRanking.cap(chunks, 2).stream().map(RetrievedChunk::getId).toList());
        assertTrue(ChunkRanking.cap(List.of(), 5).isEmpty());
    }

    @Test
    @DisplayName("批次最高分：空批次为 0")
    void reportsTopScore() {
        assertEquals(0F, ChunkRanking.topScoreOf(List.of()));
        assertEquals(0.7F, ChunkRanking.topScoreOf(List.of(chunk("a", 0.7F), chunk("b", 0.2F))));
        assertEquals(0F, ChunkRanking.topScoreOf(List.of(chunk("a", null))), "全为空分时按 0");
    }

    private RetrievedChunk chunk(String id, Float score) {
        return RetrievedChunk.builder().id(id).text("正文-" + id).score(score).build();
    }
}

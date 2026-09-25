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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 通道出口统一的候选排序工具
 * <p>
 * 各通道原先各写一份排序：有的用 {@code comparing(score).reversed()}，有的漏了空分数处理，
 * 同分时顺序还取决于底层返回顺序——表现为「同一问题两次检索，候选顺序不一致」。
 * 这里收口成一份比较器：分数降序、空分数沉底、同分按 chunk id 稳定排序
 */
public final class ChunkRanking {

    /**
     * 分数降序（缺失分数视为最低），同分按 chunk id 升序保证稳定
     */
    public static final Comparator<RetrievedChunk> BY_SCORE_DESC =
            Comparator.comparing(ChunkRanking::scoreOf, Comparator.reverseOrder())
                    .thenComparing(chunk -> chunk.getId() == null ? "" : chunk.getId());

    private ChunkRanking() {
    }

    /**
     * 返回排序后的新列表（不修改入参顺序）
     */
    public static List<RetrievedChunk> sortedByScore(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> sorted = new ArrayList<>(chunks);
        sorted.sort(BY_SCORE_DESC);
        return sorted;
    }

    /**
     * 批次最高分，空批次返回 0，用于归因日志与阈值比对
     */
    public static float topScoreOf(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return 0F;
        }
        return chunks.stream()
                .map(RetrievedChunk::getScore)
                .filter(score -> score != null && Float.isFinite(score))
                .max(Float::compare)
                .orElse(0F);
    }

    /**
     * 截断到上限；limit <= 0 表示不截断
     */
    public static List<RetrievedChunk> cap(List<RetrievedChunk> chunks, int limit) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        if (limit <= 0 || chunks.size() <= limit) {
            return List.copyOf(chunks);
        }
        return List.copyOf(chunks.subList(0, limit));
    }

    private static Float scoreOf(RetrievedChunk chunk) {
        return chunk == null || chunk.getScore() == null ? Float.NEGATIVE_INFINITY : chunk.getScore();
    }
}

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

package com.hnu.ragent.rag.core.retrieve;

import cn.hutool.core.util.StrUtil;
import com.hnu.ragent.rag.config.RAGDefaultProperties;
import com.hnu.ragent.framework.convention.RetrievedChunk;
import com.hnu.ragent.infra.embedding.EmbeddingService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "milvus", matchIfMissing = true)
public class MilvusRetrieverService implements RetrieverService {

    private final EmbeddingService embeddingService;
    private final MilvusClientV2 milvusClient;
    private final RAGDefaultProperties ragDefaultProperties;

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest retrieveParam) {
        List<Float> emb = embeddingService.embed(retrieveParam.getQuery());
        float[] vec = toArray(emb);

        float[] norm = normalize(vec);

        return retrieveByVector(norm, retrieveParam);
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest retrieveParam) {
        List<String> collectionNames = retrieveParam.getEffectiveCollectionNames();
        // 本仓库 Milvus 为「每个知识库一个物理 Collection」：多库时逐库检索后合并，topK 是总预算
        if (collectionNames.size() > 1) {
            return searchAcrossCollections(vector, collectionNames, retrieveParam.getTopK());
        }
        String collectionName = collectionNames.isEmpty() ? null : collectionNames.get(0);
        return searchSingleCollection(vector, collectionName, retrieveParam.getTopK());
    }

    /**
     * 逐物理 Collection 检索后按分数合并、截断到总预算
     */
    private List<RetrievedChunk> searchAcrossCollections(float[] vector, List<String> collectionNames, int topK) {
        List<RetrievedChunk> merged = new ArrayList<>();
        for (String collectionName : collectionNames) {
            try {
                merged.addAll(searchSingleCollection(vector, collectionName, topK));
            } catch (Exception e) {
                log.warn("Collection {} 检索失败，跳过该库", collectionName, e);
            }
        }
        merged.sort(Comparator.comparing(RetrievedChunk::getScore, Comparator.nullsLast(Comparator.reverseOrder())));
        return merged.size() > topK ? List.copyOf(merged.subList(0, topK)) : List.copyOf(merged);
    }

    private List<RetrievedChunk> searchSingleCollection(float[] vector, String collectionName, int topK) {
        List<BaseVector> vectors = List.of(new FloatVec(vector));

        Map<String, Object> params = new HashMap<>();
        params.put("metric_type", ragDefaultProperties.getMetricType());
        params.put("ef", 128);

        SearchReq req = SearchReq.builder()
                .collectionName(StrUtil.isBlank(collectionName)
                        ? ragDefaultProperties.getCollectionName()
                        : collectionName)
                .annsField("embedding")
                .data(vectors)
                .topK(topK)
                .searchParams(params)
                .outputFields(List.of("id", "content", "metadata"))
                .build();

        SearchResp resp = milvusClient.search(req);
        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();

        if (results == null || results.isEmpty()) {
            return List.of();
        }

        // TODO 需确认后续是否对分数较低数据进行限制，限制多少合适？0.65？
        // TODO 如果本次查询分数都较高，是否应该扩大查询范围？1.5倍？
        return results.get(0).stream()
                .map(r -> new RetrievedChunk(
                        Objects.toString(r.getEntity().get("id"), ""),
                        Objects.toString(r.getEntity().get("content"), ""),
                        r.getScore()))
                .collect(Collectors.toList());
    }

    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private static float[] normalize(float[] v) {
        double sum = 0.0;
        for (float x : v) sum += x * x;
        double len = Math.sqrt(sum);
        float[] nv = new float[v.length];
        for (int i = 0; i < v.length; i++) nv[i] = (float) (v[i] / len);
        return nv;
    }
}

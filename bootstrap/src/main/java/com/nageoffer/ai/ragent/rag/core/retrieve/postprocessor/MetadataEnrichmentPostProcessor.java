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
import com.nageoffer.ai.ragent.knowledge.service.impl.ChunkMetadataResolver;
import com.nageoffer.ai.ragent.knowledge.service.impl.ChunkMetadataResolver.ChunkMeta;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 元数据富化后置处理器
 * <p>
 * 处于处理链末端（Rerank、证据闸门之后），对最终 TopK 结果按 chunkId 回表补齐文档归属信息
 * （文档 ID、文档内序号、文档标题），供上下文组装时按文档聚合与标注来源
 * <p>
 * 只富化、不重排：保持进入时的相关性顺序不变
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataEnrichmentPostProcessor implements SearchResultPostProcessor {

    private final ChunkMetadataResolver chunkMetadataResolver;
    private final RAGConfigProperties ragConfigProperties;

    @Override
    public String getName() {
        return "MetadataEnrichmentPostProcessor";
    }

    @Override
    public int getOrder() {
        return 20;  // Rerank(10)、证据闸门(15) 之后，链末执行
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return Boolean.TRUE.equals(ragConfigProperties.getContextEnrichEnabled());
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        if (chunks.isEmpty()) {
            return chunks;
        }

        List<String> chunkIds = chunks.stream().map(RetrievedChunk::getId).toList();
        Map<String, ChunkMeta> metaById;
        try {
            metaById = chunkMetadataResolver.resolve(chunkIds);
        } catch (Exception e) {
            // 富化是增强能力：回表失败不应让整轮问答失败，退回不带来源的旧行为
            log.warn("分块元数据回表失败，跳过富化，chunks={}", chunks.size(), e);
            return chunks;
        }
        if (metaById.isEmpty()) {
            return chunks;
        }

        // 原地富化，保持相关性顺序不变
        for (RetrievedChunk chunk : chunks) {
            ChunkMeta meta = metaById.get(chunk.getId());
            if (meta == null) {
                continue;
            }
            chunk.setDocId(meta.docId());
            chunk.setChunkIndex(meta.chunkIndex());
            chunk.setDocName(meta.docName());
        }
        return chunks;
    }
}

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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.controller.request.RetrieveDebugRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RetrieveDebugVO;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RAGDebugServiceImpl 单元测试
 */
class RetrievalDebugServiceTest {

    private RAGDebugServiceImpl service;
    private SearchChannelProperties searchProperties;
    private RAGConfigProperties ragConfigProperties;
    private Executor directExecutor;

    // 可替换的 mock 列表
    private List<SearchChannel> searchChannels;
    private List<SearchResultPostProcessor> postProcessors;

    @BeforeEach
    void setUp() {
        searchProperties = new SearchChannelProperties();
        searchProperties.setDefaultTopK(10);

        ragConfigProperties = new RAGConfigProperties();
        directExecutor = Runnable::run;  // 同步执行，方便测试

        // 默认空列表，各测试自行设置
        searchChannels = List.of();
        postProcessors = List.of();

        service = new RAGDebugServiceImpl(
                searchChannels,
                postProcessors,
                searchProperties,
                ragConfigProperties,
                directExecutor
        );
    }

    // ==================== 校验 ====================

    @Test
    void shouldRejectEmptyQuestion() {
        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("");

        assertThrows(IllegalArgumentException.class, () -> service.debugRetrieve(req));
    }

    @Test
    void shouldRejectNullQuestion() {
        RetrieveDebugRequest req = new RetrieveDebugRequest();

        assertThrows(IllegalArgumentException.class, () -> service.debugRetrieve(req));
    }

    @Test
    void shouldRejectTopKExceedingMax() {
        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("test");
        req.setTopK(100);

        assertThrows(IllegalArgumentException.class, () -> service.debugRetrieve(req));
    }

    @Test
    void shouldAcceptTopKAtMax() {
        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("test");
        req.setTopK(50);

        // 不会抛异常
        RetrieveDebugVO vo = service.debugRetrieve(req);
        assertEquals(50, vo.getTopK());
    }

    @Test
    void shouldRejectTooManyCollectionNames() {
        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("test");
        req.setCollectionNames(List.of(
                "c1", "c2", "c3", "c4", "c5", "c6", "c7", "c8", "c9", "c10",
                "c11", "c12", "c13", "c14", "c15", "c16", "c17", "c18", "c19", "c20",
                "c21"
        ));

        assertThrows(IllegalArgumentException.class, () -> service.debugRetrieve(req));
    }

    // ==================== traceId ====================

    @Test
    void shouldGenerateTraceId() {
        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("test");

        RetrieveDebugVO vo = service.debugRetrieve(req);

        assertNotNull(vo.getTraceId());
        assertFalse(vo.getTraceId().isBlank());
    }

    // ==================== TopK 默认值 ====================

    @Test
    void shouldUseDefaultTopKWhenNotSpecified() {
        searchProperties.setDefaultTopK(10);

        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("test");

        RetrieveDebugVO vo = service.debugRetrieve(req);

        assertEquals(10, vo.getTopK());
    }

    @Test
    void shouldUseRequestTopKWhenSpecified() {
        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("test");
        req.setTopK(5);

        RetrieveDebugVO vo = service.debugRetrieve(req);

        assertEquals(5, vo.getTopK());
    }

    // ==================== 通道执行 ====================

    @Test
    void shouldExecuteEnabledChannelAndCaptureResults() {
        // given
        SearchChannel mockChannel = createMockChannel(
                "MockChannel", SearchChannelType.VECTOR_GLOBAL, 1,
                true,  // isEnabled
                List.of(
                        buildChunk("chunk-1", "content one", 0.95f),
                        buildChunk("chunk-2", "content two", 0.80f)
                )
        );
        rebuildService(List.of(mockChannel), List.of());

        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("test question");
        req.setTopK(3);

        // when
        RetrieveDebugVO vo = service.debugRetrieve(req);

        // then
        assertNotNull(vo.getTraceId());
        assertEquals(1, vo.getChannels().size());

        RetrieveDebugVO.ChannelDebugInfo channelInfo = vo.getChannels().get(0);
        assertTrue(channelInfo.isEnabled());
        assertEquals("MockChannel", channelInfo.getChannelName());
        assertEquals(SearchChannelType.VECTOR_GLOBAL, channelInfo.getChannelType());
        assertEquals(2, channelInfo.getChunkCount());
        assertEquals(2, channelInfo.getChunks().size());
        assertEquals("chunk-1", channelInfo.getChunks().get(0).getId());
        assertEquals(0.95, channelInfo.getChunks().get(0).getScore(), 0.001);

        assertEquals(2, vo.getMergedChunkCount());
        assertEquals(2, vo.getFinalChunkCount());
    }

    @Test
    void shouldSkipDisabledChannel() {
        // given
        SearchChannel disabledChannel = createMockChannel(
                "DisabledChannel", SearchChannelType.INTENT_DIRECTED, 1,
                false,  // isEnabled
                List.of()
        );
        // 需要 search() 被调用时也能返回东西，但 should not be called
        rebuildService(List.of(disabledChannel), List.of());

        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("test");

        // when
        RetrieveDebugVO vo = service.debugRetrieve(req);

        // then
        assertEquals(1, vo.getChannels().size());
        RetrieveDebugVO.ChannelDebugInfo info = vo.getChannels().get(0);
        assertFalse(info.isEnabled());
        assertNotNull(info.getSkipReason());
        assertEquals(0, info.getChunkCount());
        assertTrue(info.getChunks().isEmpty());
        assertEquals(0, vo.getMergedChunkCount());
    }

    @Test
    void shouldForceEnableChannelsViaWhitelist() {
        // given: 通道自身 isEnabled=false，但用户白名单强制启用
        SearchChannel channel = createMockChannel(
                "ForceEnabled", SearchChannelType.VECTOR_GLOBAL, 1,
                false,  // 自身 isEnabled 返回 false
                List.of(buildChunk("c1", "forced content", 0.7f))
        );
        rebuildService(List.of(channel), List.of());

        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("test");
        req.setEnableChannels(List.of(SearchChannelType.VECTOR_GLOBAL));

        // when
        RetrieveDebugVO vo = service.debugRetrieve(req);

        // then
        assertEquals(1, vo.getChannels().size());
        assertTrue(vo.getChannels().get(0).isEnabled());
        assertEquals(1, vo.getChannels().get(0).getChunkCount());
    }

    @Test
    void shouldNotForceEnableChannelsNotInWhitelist() {
        // given
        SearchChannel vecChannel = createMockChannel(
                "VectorChannel", SearchChannelType.VECTOR_GLOBAL, 1,
                true, List.of(buildChunk("c1", "content", 0.5f))
        );
        rebuildService(List.of(vecChannel), List.of());

        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("test");
        req.setEnableChannels(List.of(SearchChannelType.INTENT_DIRECTED));  // 只允许 INTENT_DIRECTED

        // when
        RetrieveDebugVO vo = service.debugRetrieve(req);

        // then
        RetrieveDebugVO.ChannelDebugInfo info = vo.getChannels().get(0);
        assertFalse(info.isEnabled());
    }

    // ==================== 通道异常处理 ====================

    @Test
    void shouldSurviveChannelException() {
        // given
        SearchChannel brokenChannel = new SearchChannel() {
            @Override
            public String getName() { return "BrokenChannel"; }
            @Override
            public int getPriority() { return 1; }
            @Override
            public boolean isEnabled(SearchContext ctx) { return true; }
            @Override
            public SearchChannelResult search(SearchContext ctx) { throw new RuntimeException("BOOM"); }
            @Override
            public SearchChannelType getType() { return SearchChannelType.VECTOR_GLOBAL; }
        };
        rebuildService(List.of(brokenChannel), List.of());

        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("test");

        // when: 不应抛异常
        RetrieveDebugVO vo = service.debugRetrieve(req);

        // then
        assertEquals(1, vo.getChannels().size());
        RetrieveDebugVO.ChannelDebugInfo info = vo.getChannels().get(0);
        assertTrue(info.isEnabled());
        assertTrue(info.getSkipReason().contains("BOOM"));
        assertEquals(0, info.getChunkCount());
        assertEquals(0, vo.getFinalChunkCount());
    }

    // ==================== 后处理链 ====================

    @Test
    void shouldExecuteDedupAndRerankInOrder() {
        // given: 通道返回有重复的 chunk（id相同）
        SearchChannel channel = createMockChannel(
                "TestChannel", SearchChannelType.VECTOR_GLOBAL, 1,
                true,
                List.of(
                        buildChunk("dup-1", "content A", 0.9f),
                        buildChunk("dup-1", "content A", 0.7f),  // 重复
                        buildChunk("unique", "content B", 0.5f)
                )
        );

        // 去重处理器
        SearchResultPostProcessor dedup = new SearchResultPostProcessor() {
            @Override
            public String getName() { return "Deduplication"; }
            @Override
            public int getOrder() { return 1; }
            @Override
            public boolean isEnabled(SearchContext ctx) { return true; }
            @Override
            public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                                 List<SearchChannelResult> results,
                                                 SearchContext ctx) {
                // 简单按 id 去重，保留第一个
                return chunks.stream()
                        .collect(java.util.LinkedHashMap<String, RetrievedChunk>::new,
                                (m, c) -> m.putIfAbsent(c.getId(), c),
                                java.util.LinkedHashMap::putAll)
                        .values().stream().toList();
            }
        };

        // Rerank（TopK截断到1）
        SearchResultPostProcessor rerank = new SearchResultPostProcessor() {
            @Override
            public String getName() { return "Rerank"; }
            @Override
            public int getOrder() { return 10; }
            @Override
            public boolean isEnabled(SearchContext ctx) { return true; }
            @Override
            public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                                 List<SearchChannelResult> results,
                                                 SearchContext ctx) {
                return chunks.size() > 1 ? chunks.subList(0, 1) : chunks;
            }
        };

        rebuildService(List.of(channel), List.of(dedup, rerank));

        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("test");
        req.setTopK(1);

        // when
        RetrieveDebugVO vo = service.debugRetrieve(req);

        // then
        assertEquals(2, vo.getPostProcessStages().size());

        // 阶段1: 去重
        RetrieveDebugVO.PostProcessStageInfo dedupStage = vo.getPostProcessStages().get(0);
        assertEquals("Deduplication", dedupStage.getStageName());
        assertTrue(dedupStage.isEnabled());
        assertEquals(3, dedupStage.getInputCount());
        assertEquals(2, dedupStage.getOutputCount());
        assertEquals(1, dedupStage.getRemovedCount());

        // 阶段2: Rerank
        RetrieveDebugVO.PostProcessStageInfo rerankStage = vo.getPostProcessStages().get(1);
        assertEquals("Rerank", rerankStage.getStageName());
        assertTrue(rerankStage.isEnabled());
        assertEquals(2, rerankStage.getInputCount());
        assertEquals(1, rerankStage.getOutputCount());

        assertEquals(1, vo.getFinalChunkCount());
    }

    @Test
    void shouldSkipDedupWhenDisabledByRequest() {
        SearchChannel channel = createMockChannel(
                "TestChannel", SearchChannelType.VECTOR_GLOBAL, 1,
                true,
                List.of(buildChunk("c1", "content", 0.9f), buildChunk("c1", "content", 0.7f))
        );
        // 去重处理器，即使 isEnabled=true，也会被请求级 disable
        SearchResultPostProcessor dedup = new SearchResultPostProcessor() {
            @Override
            public String getName() { return "Deduplication"; }
            @Override
            public int getOrder() { return 1; }
            @Override
            public boolean isEnabled(SearchContext ctx) { return true; }
            @Override
            public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                                 List<SearchChannelResult> results,
                                                 SearchContext ctx) {
                return chunks;
            }
        };
        rebuildService(List.of(channel), List.of(dedup));

        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("test");
        req.setEnableDedup(false);

        // when
        RetrieveDebugVO vo = service.debugRetrieve(req);

        // then
        assertEquals(1, vo.getPostProcessStages().size());
        assertFalse(vo.getPostProcessStages().get(0).isEnabled());
        assertEquals("enableDedup=false，跳过去重", vo.getPostProcessStages().get(0).getSkipReason());
    }

    @Test
    void shouldSkipRerankWhenDisabledByRequest() {
        SearchChannel channel = createMockChannel(
                "TestChannel", SearchChannelType.VECTOR_GLOBAL, 1,
                true,
                List.of(buildChunk("c1", "content", 0.9f))
        );
        SearchResultPostProcessor rerank = new SearchResultPostProcessor() {
            @Override
            public String getName() { return "Rerank"; }
            @Override
            public int getOrder() { return 10; }
            @Override
            public boolean isEnabled(SearchContext ctx) { return true; }
            @Override
            public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                                 List<SearchChannelResult> results,
                                                 SearchContext ctx) {
                return chunks;
            }
        };
        rebuildService(List.of(channel), List.of(rerank));

        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("test");
        req.setEnableRerank(false);

        // when
        RetrieveDebugVO vo = service.debugRetrieve(req);

        // then
        assertEquals(1, vo.getPostProcessStages().size());
        assertFalse(vo.getPostProcessStages().get(0).isEnabled());
    }

    // ==================== debugMode ====================

    @Test
    void shouldNotReturnFullTextWhenDebugModeFalse() {
        String longText = "A".repeat(600);
        SearchChannel channel = createMockChannel(
                "Test", SearchChannelType.VECTOR_GLOBAL, 1,
                true, List.of(buildChunk("c1", longText, 0.9f))
        );
        rebuildService(List.of(channel), List.of());

        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("test");
        req.setDebugMode(false);

        RetrieveDebugVO vo = service.debugRetrieve(req);

        RetrieveDebugVO.ChunkDebugItem item = vo.getChannels().get(0).getChunks().get(0);
        assertNull(item.getTextFull());
        assertEquals(500, item.getText().length());
    }

    @Test
    void shouldReturnFullTextWhenDebugModeTrue() {
        String longText = "A".repeat(600);
        SearchChannel channel = createMockChannel(
                "Test", SearchChannelType.VECTOR_GLOBAL, 1,
                true, List.of(buildChunk("c1", longText, 0.9f))
        );
        rebuildService(List.of(channel), List.of());

        RetrieveDebugRequest req = new RetrieveDebugRequest();
        req.setQuestion("test");
        req.setDebugMode(true);

        RetrieveDebugVO vo = service.debugRetrieve(req);

        RetrieveDebugVO.ChunkDebugItem item = vo.getChannels().get(0).getChunks().get(0);
        assertEquals(longText, item.getTextFull());
        assertEquals(500, item.getText().length());  // text 仍截断
    }

    // ==================== 辅助方法 ====================

    private void rebuildService(List<SearchChannel> channels,
                                 List<SearchResultPostProcessor> processors) {
        this.searchChannels = channels;
        this.postProcessors = processors;
        this.service = new RAGDebugServiceImpl(
                channels, processors, searchProperties, ragConfigProperties, directExecutor
        );
    }

    private SearchChannel createMockChannel(String name,
                                             SearchChannelType type,
                                             int priority,
                                             boolean enabled,
                                             List<RetrievedChunk> chunks) {
        return new SearchChannel() {
            @Override
            public String getName() { return name; }
            @Override
            public int getPriority() { return priority; }
            @Override
            public boolean isEnabled(SearchContext ctx) { return enabled; }
            @Override
            public SearchChannelResult search(SearchContext ctx) {
                return SearchChannelResult.builder()
                        .channelType(type)
                        .channelName(name)
                        .chunks(chunks)
                        .latencyMs(42)
                        .metadata(Map.of())
                        .build();
            }
            @Override
            public SearchChannelType getType() { return type; }
        };
    }

    private RetrievedChunk buildChunk(String id, String text, float score) {
        return RetrievedChunk.builder()
                .id(id)
                .text(text)
                .score(score)
                .build();
    }
}

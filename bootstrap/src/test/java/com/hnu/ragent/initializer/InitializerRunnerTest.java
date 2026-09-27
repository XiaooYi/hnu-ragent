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

package com.hnu.ragent.initializer;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hnu.ragent.ingestion.service.IntentTreeService;
import com.hnu.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.hnu.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.hnu.ragent.knowledge.service.KnowledgeBaseService;
import com.hnu.ragent.knowledge.service.KnowledgeDocumentService;
import com.hnu.ragent.rag.controller.request.IntentNodeCreateRequest;
import com.hnu.ragent.rag.controller.request.SampleQuestionCreateRequest;
import com.hnu.ragent.rag.controller.vo.IntentNodeTreeVO;
import com.hnu.ragent.rag.controller.vo.SampleQuestionVO;
import com.hnu.ragent.rag.service.SampleQuestionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 初始化编排：幂等跳过、KB 意图自动绑库、清理只删本数据集的数据
 */
class InitializerRunnerTest {

    private final InitializerDatasetLoader loader = mock(InitializerDatasetLoader.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final KnowledgeDocumentService knowledgeDocumentService = mock(KnowledgeDocumentService.class);
    private final SampleQuestionService sampleQuestionService = mock(SampleQuestionService.class);
    private final IntentTreeService intentTreeService = mock(IntentTreeService.class);

    @Test
    @DisplayName("空环境灌数据：建知识库、示例问题与意图节点，KB 节点自动绑库")
    void seedsOnEmptyEnvironment() {
        InitializerProperties properties = properties("seed");
        InitializerRunner runner = runner(properties, datasetWithoutDocs());

        when(knowledgeBaseService.pageQuery(any())).thenReturn(emptyPage());
        when(knowledgeBaseService.create(any())).thenReturn("kb-1");
        when(sampleQuestionService.pageQuery(any())).thenReturn(emptyPage());
        when(intentTreeService.getFullTree()).thenReturn(List.of());

        runner.run(null);

        verify(knowledgeBaseService).create(any(KnowledgeBaseCreateRequest.class));
        ArgumentCaptor<SampleQuestionCreateRequest> questionCaptor =
                ArgumentCaptor.forClass(SampleQuestionCreateRequest.class);
        verify(sampleQuestionService).create(questionCaptor.capture());
        assertEquals("转专业需要哪些材料", questionCaptor.getValue().getQuestion());

        ArgumentCaptor<IntentNodeCreateRequest> intentCaptor =
                ArgumentCaptor.forClass(IntentNodeCreateRequest.class);
        verify(intentTreeService, times(2)).createNode(intentCaptor.capture());
        IntentNodeCreateRequest kbNode = intentCaptor.getAllValues().get(1);
        assertEquals("kb-1", kbNode.getKbId());
        assertEquals(List.of("hnu_demo_store"), kbNode.getCollectionNames());

        assertEquals(1, runner.summary().created().stream().filter(item -> item.startsWith("知识库")).count());
        assertTrue(runner.summary().failed().isEmpty());
    }

    @Test
    @DisplayName("重复执行幂等：已存在的知识库、问题与节点全部跳过")
    void skipsWhenAlreadySeeded() {
        InitializerRunner runner = runner(properties("seed"), datasetWithoutDocs());

        when(knowledgeBaseService.pageQuery(any())).thenReturn(pageOf(knowledgeBaseVO("kb-1", "已存在")));
        when(sampleQuestionService.pageQuery(any())).thenReturn(pageOf(SampleQuestionVO.builder()
                .id("q-1").question("转专业需要哪些材料").build()));
        when(intentTreeService.getFullTree()).thenReturn(List.of(
                IntentNodeTreeVO.builder()
                        .id("n-1").intentCode("init_hnu")
                        .children(List.of(IntentNodeTreeVO.builder()
                                .id("n-2").intentCode("init_hnu_teaching").build()))
                        .build()));

        runner.run(null);

        verify(knowledgeBaseService, never()).create(any());
        verify(sampleQuestionService, never()).create(any());
        verify(intentTreeService, never()).createNode(any());
        assertTrue(runner.summary().created().isEmpty());
        assertTrue(runner.summary().skipped().size() >= 3);
    }

    @Test
    @DisplayName("清理模式：只删本数据集前缀的意图、声明的示例问题与知识库")
    void cleanupRemovesOnlyOwnData() {
        InitializerRunner runner = runner(properties("cleanup"), datasetWithoutDocs());

        when(intentTreeService.getFullTree()).thenReturn(List.of(
                IntentNodeTreeVO.builder()
                        .id("n-1").intentCode("init_hnu")
                        .children(List.of(IntentNodeTreeVO.builder().id("n-2").intentCode("init_hnu_teaching").build()))
                        .build(),
                IntentNodeTreeVO.builder().id("n-3").intentCode("custom_node").build()));
        when(sampleQuestionService.pageQuery(any())).thenReturn(pageOf(
                SampleQuestionVO.builder().id("q-1").question("转专业需要哪些材料").build(),
                SampleQuestionVO.builder().id("q-2").question("用户自己加的问题").build()));
        when(knowledgeBaseService.pageQuery(any())).thenReturn(pageOf(knowledgeBaseVO("kb-1", "示例库")));

        runner.run(null);

        verify(intentTreeService, times(2)).deleteNode(any(String.class));
        verify(intentTreeService, never()).deleteNode("n-3");
        verify(sampleQuestionService).delete("q-1");
        verify(sampleQuestionService, never()).delete("q-2");
        verify(knowledgeBaseService).delete("kb-1");
    }

    private InitializerProperties properties(String mode) {
        InitializerProperties properties = new InitializerProperties();
        properties.setEnabled(true);
        properties.setScenario("test");
        properties.setMode(mode);
        return properties;
    }

    private KnowledgeBaseVO knowledgeBaseVO(String id, String name) {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        vo.setId(id);
        vo.setName(name);
        vo.setCollectionName("hnu_demo_store");
        return vo;
    }

    private InitializerRunner runner(InitializerProperties properties, InitializerDataset dataset) {
        when(loader.load(properties.getScenario())).thenReturn(dataset);
        return new InitializerRunner(properties, loader, knowledgeBaseService, knowledgeDocumentService,
                sampleQuestionService, intentTreeService);
    }

    /**
     * 不带文档目录的数据集：文档导入依赖真实文件系统与对象存储，另由人工验收覆盖
     */
    private InitializerDataset datasetWithoutDocs() {
        return new InitializerDataset("测试库", "hnu_demo_store", "qwen3.7-text-embedding", "",
                "init_", List.of("转专业需要哪些材料"),
                List.of(
                        new InitializerDataset.IntentSeed("init_hnu", "根", 0, null, 1, "入口", List.of()),
                        new InitializerDataset.IntentSeed("init_hnu_teaching", "教学", 2, "init_hnu", 0, "教学事务",
                                List.of("转专业"))));
    }

    private <T> Page<T> emptyPage() {
        Page<T> page = new Page<>(1, 500);
        page.setRecords(List.of());
        return page;
    }

    @SafeVarargs
    private <T> Page<T> pageOf(T... records) {
        Page<T> page = new Page<>(1, 500);
        page.setRecords(List.of(records));
        return page;
    }
}

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

package com.nageoffer.ai.ragent.initializer;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.initializer.InitializerDataset.IntentSeed;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBaseCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeBasePageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentPageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeDocumentUploadRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeBaseVO;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeDocumentVO;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeBaseService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentService;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.SampleQuestionCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.SampleQuestionPageRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.IntentNodeTreeVO;
import com.nageoffer.ai.ragent.rag.controller.vo.SampleQuestionVO;
import com.nageoffer.ai.ragent.rag.service.SampleQuestionService;
import com.nageoffer.ai.ragent.ingestion.service.IntentTreeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 场景初始化器
 * <p>
 * 用途：把「一个能跑的场景」一次装好——知识库（含示例文档）、示例问题、示例意图树。
 * 全部走既有的 Service 层（同一套校验与副作用，如建桶 / 建向量空间 / 清缓存），
 * 而不是绕过业务直接写库
 * <p>
 * 幂等与安全：
 * <ul>
 *   <li>知识库按 {@code collection-name} 判定已存在；示例问题按问题原文去重；意图节点按 code 判定</li>
 *   <li>意图节点只处理数据集声明的 code（默认 {@code init_} 前缀），清理也按同一批 code，不会误伤自建意图</li>
 *   <li>默认关闭（{@code initializer.enabled=false}），需要显式打开；{@code mode=cleanup} 只删除本数据集写入的数据</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "initializer", name = "enabled", havingValue = "true")
public class InitializerRunner implements ApplicationRunner {

    private static final String MODE_CLEANUP = "cleanup";

    private final InitializerProperties properties;
    private final InitializerDatasetLoader datasetLoader;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeDocumentService knowledgeDocumentService;
    private final SampleQuestionService sampleQuestionService;
    private final IntentTreeService intentTreeService;
    private final InitializerSummary summary = new InitializerSummary();

    /**
     * 供测试读取初始化过程记录
     */
    InitializerSummary summary() {
        return summary;
    }

    @Override
    public void run(ApplicationArguments args) {
        InitializerDataset dataset = datasetLoader.load(properties.getScenario());
        log.info("初始化器启动, scenario={}, mode={}, collection={}",
                properties.getScenario(), properties.getMode(), dataset.collectionName());
        try {
            if (MODE_CLEANUP.equalsIgnoreCase(properties.getMode())) {
                cleanup(dataset);
            } else {
                seed(dataset);
            }
        } catch (Exception e) {
            if (Boolean.FALSE.equals(properties.getFailFast())) {
                log.warn("初始化中断（fail-fast=false，继续收尾）, scenario={}", properties.getScenario(), e);
            } else {
                throw new IllegalStateException("场景初始化失败: " + properties.getScenario(), e);
            }
        }
        log.info("初始化器结束, 结果={}", summary.render());
    }

    private void seed(InitializerDataset dataset) {
        String kbId = ensureKnowledgeBase(dataset);
        importDocuments(dataset, kbId);
        seedSampleQuestions(dataset);
        seedIntents(dataset, kbId);
    }

    private String ensureKnowledgeBase(InitializerDataset dataset) {
        KnowledgeBaseVO existing = findKnowledgeBase(dataset.collectionName());
        if (existing != null) {
            summary.skipped("知识库已存在：" + dataset.collectionName());
            return existing.getId();
        }
        KnowledgeBaseCreateRequest request = new KnowledgeBaseCreateRequest();
        request.setName(dataset.name());
        request.setCollectionName(dataset.collectionName());
        request.setEmbeddingModel(dataset.embeddingModel());
        String kbId = knowledgeBaseService.create(request);
        summary.created("知识库：" + dataset.name());
        return kbId;
    }

    private void importDocuments(InitializerDataset dataset, String kbId) {
        if (StrUtil.isBlank(dataset.docsDir())) {
            summary.skipped("数据集未声明 docs-dir，跳过文档导入");
            return;
        }
        Path dir = Paths.get(dataset.docsDir());
        if (!Files.isDirectory(dir)) {
            summary.skipped("文档目录不存在：" + dir);
            return;
        }
        List<String> existingNames = existingDocumentNames(kbId);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                String fileName = file.getFileName().toString();
                if (existingNames.contains(fileName)) {
                    summary.skipped("文档已存在：" + fileName);
                    continue;
                }
                try {
                    KnowledgeDocumentUploadRequest request = new KnowledgeDocumentUploadRequest();
                    request.setSourceType("file");
                    request.setProcessMode("chunk");
                    request.setChunkStrategy("fixed_size");
                    KnowledgeDocumentVO document = knowledgeDocumentService.upload(
                            kbId, request, new PathMultipartFile(file, contentType(fileName)));
                    knowledgeDocumentService.startChunk(document.getId());
                    summary.created("文档：" + fileName);
                } catch (Exception e) {
                    // 单个文档失败不拖垮整个场景：补齐后重跑初始化器即可（幂等）
                    log.warn("文档导入失败，已跳过: {}", fileName, e);
                    summary.failed("文档：" + fileName);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取文档目录失败: " + dir, e);
        }
    }

    private void seedSampleQuestions(InitializerDataset dataset) {
        if (dataset.sampleQuestions().isEmpty()) {
            return;
        }
        List<String> existing = existingSampleQuestions();
        for (String question : dataset.sampleQuestions()) {
            if (existing.contains(question)) {
                summary.skipped("示例问题已存在：" + question);
                continue;
            }
            SampleQuestionCreateRequest request = new SampleQuestionCreateRequest();
            request.setQuestion(question);
            sampleQuestionService.create(request);
            summary.created("示例问题：" + question);
        }
    }

    private void seedIntents(InitializerDataset dataset, String kbId) {
        if (dataset.intents().isEmpty()) {
            return;
        }
        List<String> existingCodes = existingIntentCodes();
        for (IntentSeed seed : dataset.intents()) {
            if (existingCodes.contains(seed.code())) {
                summary.skipped("意图节点已存在：" + seed.code());
                continue;
            }
            IntentNodeCreateRequest request = new IntentNodeCreateRequest();
            request.setIntentCode(seed.code());
            request.setName(seed.name());
            request.setLevel(seed.level());
            request.setParentCode(seed.parentCode());
            request.setKind(seed.kind());
            request.setDescription(seed.description());
            request.setExamples(seed.examples().isEmpty() ? null : seed.examples());
            request.setSortOrder(0);
            request.setEnabled(1);
            if (seed.isKb()) {
                // KB 意图必须绑库，否则意图定向检索拿不到目标 collection
                request.setKbId(kbId);
                request.setCollectionNames(List.of(dataset.collectionName()));
            }
            intentTreeService.createNode(request);
            summary.created("意图节点：" + seed.code());
        }
    }

    private void cleanup(InitializerDataset dataset) {
        for (IntentNodeTreeVO node : intentTreeService.getFullTree()) {
            deleteIntentRecursively(node, dataset.intentCodePrefix());
        }
        List<String> seededQuestions = List.copyOf(dataset.sampleQuestions());
        for (SampleQuestionVO question : listSampleQuestions()) {
            if (seededQuestions.contains(question.getQuestion())) {
                sampleQuestionService.delete(question.getId());
                summary.deleted("示例问题：" + question.getQuestion());
            }
        }
        KnowledgeBaseVO kb = findKnowledgeBase(dataset.collectionName());
        if (kb != null) {
            knowledgeBaseService.delete(kb.getId());
            summary.deleted("知识库：" + dataset.collectionName());
        }
    }

    private void deleteIntentRecursively(IntentNodeTreeVO node, String prefix) {
        if (node == null) {
            return;
        }
        if (node.getChildren() != null) {
            node.getChildren().forEach(child -> deleteIntentRecursively(child, prefix));
        }
        String code = node.getIntentCode();
        if (StrUtil.isNotBlank(code) && code.startsWith(prefix)) {
            intentTreeService.deleteNode(node.getId());
            summary.deleted("意图节点：" + code);
        }
    }

    private KnowledgeBaseVO findKnowledgeBase(String collectionName) {
        KnowledgeBasePageRequest request = new KnowledgeBasePageRequest();
        request.setCurrent(1);
        request.setSize(200);
        return knowledgeBaseService.pageQuery(request).getRecords().stream()
                .filter(kb -> Objects.equals(collectionName, kb.getCollectionName()))
                .findFirst()
                .orElse(null);
    }

    private List<String> existingDocumentNames(String kbId) {
        KnowledgeDocumentPageRequest request = new KnowledgeDocumentPageRequest();
        request.setCurrent(1);
        request.setSize(500);
        return knowledgeDocumentService.page(kbId, request).getRecords().stream()
                .map(KnowledgeDocumentVO::getDocName)
                .filter(StrUtil::isNotBlank)
                .toList();
    }

    private List<String> existingSampleQuestions() {
        return listSampleQuestions().stream()
                .map(SampleQuestionVO::getQuestion)
                .filter(StrUtil::isNotBlank)
                .toList();
    }

    private List<SampleQuestionVO> listSampleQuestions() {
        SampleQuestionPageRequest request = new SampleQuestionPageRequest();
        request.setCurrent(1);
        request.setSize(500);
        return List.copyOf(sampleQuestionService.pageQuery(request).getRecords());
    }

    private List<String> existingIntentCodes() {
        List<String> codes = new ArrayList<>();
        intentTreeService.getFullTree().forEach(node -> collectCodes(node, codes));
        return codes;
    }

    private void collectCodes(IntentNodeTreeVO node, List<String> codes) {
        if (node == null) {
            return;
        }
        if (StrUtil.isNotBlank(node.getIntentCode())) {
            codes.add(node.getIntentCode());
        }
        if (node.getChildren() != null) {
            node.getChildren().forEach(child -> collectCodes(child, codes));
        }
    }

    /**
     * 按扩展名给个基础 MIME：上传链路会再用 Tika 复核并拒绝无解析器的类型
     */
    private String contentType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".md")) {
            return "text/markdown";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        return "application/octet-stream";
    }
}

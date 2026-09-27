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

package com.hnu.ragent.rag.core.guidance;

import com.hnu.ragent.rag.config.GuidanceProperties;
import com.hnu.ragent.rag.core.intent.IntentNode;
import com.hnu.ragent.rag.core.intent.IntentNodeRegistry;
import com.hnu.ragent.rag.core.intent.NodeScore;
import com.hnu.ragent.rag.core.prompt.PromptTemplateLoader;
import com.hnu.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 歧义判定：候选路径重名才进入 LLM 确认
 */
class IntentGuidanceServiceTest {

    private final IntentNodeRegistry registry = mock(IntentNodeRegistry.class);
    private final PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
    private final AmbiguityLLMChecker checker = mock(AmbiguityLLMChecker.class);

    private IntentGuidanceService service;

    @BeforeEach
    void setUp() {
        GuidanceProperties properties = new GuidanceProperties();
        properties.setEnabled(true);
        properties.setMaxOptions(6);
        when(promptTemplateLoader.render(anyString(), anyMap())).thenReturn("澄清提示");
        service = new IntentGuidanceService(properties, registry, promptTemplateLoader, checker);
    }

    @Test
    @DisplayName("分数接近但路径不重名时不再判定歧义（比值不再参与判定）")
    void doesNotTriggerOnCloseScoresWithoutNameConflict() {
        IntentNode teaching = kbNode("teaching", "本科教学", "root");
        IntentNode scholarship = kbNode("scholarship", "奖助学金", "root");
        stubRegistry(teaching, scholarship, rootNode());

        GuidanceDecision decision = detect("本科教学和奖助学金都有哪些规定",
                List.of(score(teaching, 0.92), score(scholarship, 0.9)));

        assertFalse(decision.isPrompt());
    }

    @Test
    @DisplayName("叶子重名且路径不同时进入 LLM 确认，确认通过才澄清")
    void triggersOnDuplicateLeafName() {
        IntentNode undergrad = kbNode("u-topic", "转专业", "u-cat");
        IntentNode postgrad = kbNode("p-topic", "转专业", "p-cat");
        stubRegistry(undergrad, postgrad, cat("u-cat", "本科教学"), cat("p-cat", "研究生培养"), rootNode());
        when(checker.checkAmbiguity(anyString(), any())).thenReturn(true);

        GuidanceDecision decision = detect("转专业", List.of(score(undergrad, 0.9), score(postgrad, 0.8)));

        assertTrue(decision.isPrompt());
    }

    @Test
    @DisplayName("LLM 判定不构成歧义时放行检索")
    void llmRejectionSkipsClarification() {
        IntentNode undergrad = kbNode("u-topic", "转专业", "u-cat");
        IntentNode postgrad = kbNode("p-topic", "转专业", "p-cat");
        stubRegistry(undergrad, postgrad, cat("u-cat", "本科教学"), cat("p-cat", "研究生培养"), rootNode());
        when(checker.checkAmbiguity(anyString(), any())).thenReturn(false);

        GuidanceDecision decision = detect("转专业", List.of(score(undergrad, 0.9), score(postgrad, 0.8)));

        assertFalse(decision.isPrompt());
    }

    @Test
    @DisplayName("分叉后的中间节点重名，用户问题未提到该名称时不判定歧义")
    void intermediateNameConflictWithoutQuestionHitIsNotAmbiguous() {
        IntentNode undergrad = kbNode("u-topic", "交换生", "u-cat");
        IntentNode postgrad = kbNode("p-topic", "公派留学", "p-cat");
        stubRegistry(undergrad, postgrad, cat("u-cat", "国际交流"), cat("p-cat", "国际交流"), rootNode());
        when(checker.checkAmbiguity(anyString(), any())).thenReturn(true);

        // 两条路径共享「国际交流」这个名字，但问题只说了「转专业」，并不站在该岔路口
        GuidanceDecision decision = detect("转专业需要哪些材料",
                List.of(score(undergrad, 0.9), score(postgrad, 0.8)));

        assertFalse(decision.isPrompt());
    }

    @Test
    @DisplayName("分叉后的中间节点重名且用户问题提到该名称时进入 LLM 确认")
    void intermediateNameConflictWithQuestionHitGoesToLlm() {
        IntentNode undergrad = kbNode("u-topic", "交换生", "u-cat");
        IntentNode postgrad = kbNode("p-topic", "公派留学", "p-cat");
        stubRegistry(undergrad, postgrad, cat("u-cat", "国际交流"), cat("p-cat", "国际交流"), rootNode());
        when(checker.checkAmbiguity(anyString(), any())).thenReturn(true);

        GuidanceDecision decision = detect("国际交流有哪些项目",
                List.of(score(undergrad, 0.9), score(postgrad, 0.8)));

        assertTrue(decision.isPrompt());
    }

    @Test
    @DisplayName("不等深路径也能比较：叶子同名即可成组")
    void comparesPathsOfDifferentDepth() {
        IntentNode shortPath = kbNode("s-topic", "选课", "s-root");
        IntentNode deepPath = kbNode("d-topic", "选课", "d-cat");
        stubRegistry(shortPath, deepPath, rootNode(), cat("d-cat", "研究生培养"), kbNode("d-root", "研究生", null));
        when(checker.checkAmbiguity(anyString(), any())).thenReturn(true);

        assertTrue(detect("选课", List.of(score(shortPath, 0.9), score(deepPath, 0.7))).isPrompt());
    }

    @Test
    @DisplayName("父链成环不会死循环")
    void toleratesCyclicParentChain() {
        IntentNode a = kbNode("a", "转专业", "b");
        IntentNode b = kbNode("b", "本科教学", "a");
        IntentNode c = kbNode("c", "转专业", null);
        stubRegistry(a, b, c);
        when(checker.checkAmbiguity(anyString(), any())).thenReturn(true);

        assertTrue(detect("转专业", List.of(score(a, 0.9), score(c, 0.8))).isPrompt());
    }

    @Test
    @DisplayName("只有一个候选时不判定歧义")
    void singleCandidateIsNotAmbiguous() {
        IntentNode teaching = kbNode("teaching", "本科教学", "root");
        stubRegistry(teaching, rootNode());

        assertFalse(detect("本科教学规定", List.of(score(teaching, 0.9))).isPrompt());
    }

    private GuidanceDecision detect(String question, List<NodeScore> scores) {
        return service.detectAmbiguity(question, List.of(new SubQuestionIntent(question, scores)));
    }

    private void stubRegistry(IntentNode... nodes) {
        Map<String, IntentNode> byId = new HashMap<>();
        for (IntentNode node : nodes) {
            byId.put(node.getId(), node);
        }
        when(registry.getNodeById(anyString())).thenAnswer(invocation -> byId.get(invocation.getArgument(0)));
    }

    private IntentNode kbNode(String id, String name, String parentId) {
        return IntentNode.builder()
                .id(id)
                .name(name)
                .parentId(parentId)
                .fullPath(name)
                .kind(com.hnu.ragent.rag.enums.IntentKind.KB)
                .build();
    }

    private IntentNode cat(String id, String name) {
        return kbNode(id, name, "root");
    }

    private IntentNode rootNode() {
        return kbNode("root", "湖大制度", null);
    }

    private NodeScore score(IntentNode node, double value) {
        return NodeScore.builder().node(node).score(value).build();
    }
}

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 初始化数据集解析：注释/空行、去重、非法行早失败
 */
class InitializerDatasetTest {

    @Test
    @DisplayName("示例问题：忽略注释与空行，按原文去重保序")
    void parsesSampleQuestions() {
        List<String> questions = InitializerDataset.parseSampleQuestions("""
                # 注释
                转专业需要哪些材料

                挂科之后怎么申请补考
                转专业需要哪些材料
                """);

        assertEquals(List.of("转专业需要哪些材料", "挂科之后怎么申请补考"), questions);
        assertTrue(InitializerDataset.parseSampleQuestions("").isEmpty());
        assertTrue(InitializerDataset.parseSampleQuestions(null).isEmpty());
    }

    @Test
    @DisplayName("意图表：解析各列、示例按 | 拆分、父节点为空表示根")
    void parsesIntents() {
        List<InitializerDataset.IntentSeed> seeds = InitializerDataset.parseIntents("""
                # code	name	level	parent	kind	description	examples
                init_hnu	湖大校内制度	0		1	制度入口	校内规定有哪些
                init_hnu_teaching	教学与学籍	1	init_hnu	1	教学口事务	转专业需要哪些材料|选课什么时候开始
                init_hnu_teaching_major	转专业	2	init_hnu_teaching	0	转专业规定	
                """);

        assertEquals(3, seeds.size());
        InitializerDataset.IntentSeed root = seeds.get(0);
        assertEquals("init_hnu", root.code());
        assertEquals(0, root.level());
        assertNull(root.parentCode());
        assertEquals(1, root.kind());
        assertTrue(!root.isKb());

        assertEquals(List.of("转专业需要哪些材料", "选课什么时候开始"), seeds.get(1).examples());
        assertTrue(seeds.get(2).isKb(), "kind=0 为知识库节点，初始化时自动绑库");
        assertTrue(seeds.get(2).examples().isEmpty());
    }

    @Test
    @DisplayName("意图表非法输入直接报错，避免灌进半套意图树")
    void failsOnInvalidIntentRows() {
        assertThrows(IllegalStateException.class, () -> InitializerDataset.parseIntents("a\tb\tc"));
        assertThrows(IllegalStateException.class, () -> InitializerDataset.parseIntents("""
                init_a	A	0		1	说明
                init_a	B	1	init_a	1	说明
                """));
        assertThrows(IllegalStateException.class, () -> InitializerDataset.parseIntents("""
                init_b	B	9		1	说明
                """));
        assertThrows(IllegalStateException.class, () -> InitializerDataset.parseIntents("""
                init_c	C	x		1	说明
                """));
    }

    @Test
    @DisplayName("scenario.properties 缺 collection-name 时报错")
    void requiresCollectionName() {
        Properties properties = new Properties();
        properties.setProperty("name", "缺库名");
        assertThrows(IllegalStateException.class,
                () -> InitializerDataset.fromProperties(properties, List.of(), ""));
    }

    @Test
    @DisplayName("classpath 数据集可加载：hnu 场景声明完整")
    void loadsHnuScenarioFromClasspath() {
        InitializerDataset dataset = new InitializerDatasetLoader(new DefaultResourceLoader()).load("hnu");

        assertEquals("hnu_demo_store", dataset.collectionName());
        assertEquals("qwen3.7-text-embedding", dataset.embeddingModel());
        assertEquals("init_", dataset.intentCodePrefix());
        assertEquals(5, dataset.sampleQuestions().size());
        assertEquals(7, dataset.intents().size());
        assertTrue(dataset.intents().stream().anyMatch(InitializerDataset.IntentSeed::isKb));
    }
}

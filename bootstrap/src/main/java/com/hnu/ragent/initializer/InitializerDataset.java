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

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * 初始化数据集
 * <p>
 * 数据集是**纯文本**（properties + tsv + txt），跟着代码走、可 review、可 diff；解析逻辑与执行逻辑分离，
 * 解析部分可脱离 Spring 单测
 *
 * @param name             数据集展示名
 * @param collectionName   知识库 collection 名（幂等键：同名即认为已初始化）
 * @param embeddingModel   嵌入模型标识（必须与 ai.embedding 候选一致）
 * @param docsDir          待导入文档目录（相对仓库根或绝对路径）
 * @param intentCodePrefix 意图节点 code 前缀（清理时按前缀精准删除，避免误伤用户自建意图）
 * @param sampleQuestions  示例问题
 * @param intents          意图节点
 */
public record InitializerDataset(String name,
                                 String collectionName,
                                 String embeddingModel,
                                 String docsDir,
                                 String intentCodePrefix,
                                 List<String> sampleQuestions,
                                 List<IntentSeed> intents) {

    public InitializerDataset {
        sampleQuestions = sampleQuestions == null ? List.of() : List.copyOf(sampleQuestions);
        intents = intents == null ? List.of() : List.copyOf(intents);
    }

    /**
     * 意图节点种子
     *
     * @param code        intentCode
     * @param name        节点名
     * @param level       0 DOMAIN / 1 CATEGORY / 2 TOPIC
     * @param parentCode  父节点 code，空表示根
     * @param kind        0 KB / 1 SYSTEM / 2 MCP
     * @param description 说明（意图分类时会作为判断依据）
     * @param examples    示例问题
     */
    public record IntentSeed(String code,
                             String name,
                             int level,
                             String parentCode,
                             int kind,
                             String description,
                             List<String> examples) {

        public IntentSeed {
            examples = examples == null ? List.of() : List.copyOf(examples);
        }

        public boolean isKb() {
            return kind == 0;
        }
    }

    /**
     * 解析 scenario.properties
     */
    public static InitializerDataset fromProperties(Properties scenario,
                                                   List<String> sampleQuestions,
                                                   String intentsTsv) {
        String collectionName = StrUtil.trimToNull(scenario.getProperty("collection-name"));
        if (collectionName == null) {
            throw new IllegalStateException("初始化数据集缺少 collection-name");
        }
        return new InitializerDataset(
                StrUtil.blankToDefault(scenario.getProperty("name"), collectionName),
                collectionName,
                StrUtil.blankToDefault(scenario.getProperty("embedding-model"), ""),
                StrUtil.blankToDefault(scenario.getProperty("docs-dir"), ""),
                StrUtil.blankToDefault(scenario.getProperty("intent-code-prefix"), "init_"),
                sampleQuestions,
                parseIntents(intentsTsv));
    }

    /**
     * 解析示例问题：一行一条，{@code #} 开头为注释，空行忽略，去重保序
     */
    public static List<String> parseSampleQuestions(String text) {
        List<String> questions = new ArrayList<>();
        if (StrUtil.isBlank(text)) {
            return questions;
        }
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (!questions.contains(trimmed)) {
                questions.add(trimmed);
            }
        }
        return questions;
    }

    /**
     * 解析意图表：制表符分隔，列序 code / name / level / parentCode / kind / description / examples(|)
     * <p>
     * 列数不足或 code 重复的行直接报错：初始化脚本写错应当立刻停下，而不是灌进半套意图树
     */
    public static List<IntentSeed> parseIntents(String tsv) {
        List<IntentSeed> seeds = new ArrayList<>();
        if (StrUtil.isBlank(tsv)) {
            return seeds;
        }
        int lineNo = 0;
        for (String line : tsv.split("\n")) {
            lineNo++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] columns = trimmed.split("\t", -1);
            if (columns.length < 6) {
                throw new IllegalStateException("意图表第 " + lineNo + " 行少于 6 列：" + trimmed);
            }
            String code = columns[0].trim();
            if (seeds.stream().anyMatch(seed -> seed.code().equals(code))) {
                throw new IllegalStateException("意图表第 " + lineNo + " 行 code 重复：" + code);
            }
            seeds.add(new IntentSeed(
                    code,
                    columns[1].trim(),
                    parseLevel(columns[2], lineNo),
                    StrUtil.trimToNull(columns[3]),
                    parseInt(columns[4], lineNo, "kind"),
                    columns[5].trim(),
                    columns.length > 6 ? parseExamples(columns[6]) : List.of()));
        }
        return seeds;
    }

    private static List<String> parseExamples(String raw) {
        List<String> examples = new ArrayList<>();
        for (String part : raw.split("\\|")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                examples.add(trimmed);
            }
        }
        return examples;
    }

    private static int parseLevel(String raw, int lineNo) {
        int level = parseInt(raw, lineNo, "level");
        if (level < 0 || level > 2) {
            throw new IllegalStateException("意图表第 " + lineNo + " 行 level 非法（0/1/2）：" + raw);
        }
        return level;
    }

    private static int parseInt(String raw, int lineNo, String column) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("意图表第 " + lineNo + " 行 " + column + " 不是数字：" + raw);
        }
    }
}

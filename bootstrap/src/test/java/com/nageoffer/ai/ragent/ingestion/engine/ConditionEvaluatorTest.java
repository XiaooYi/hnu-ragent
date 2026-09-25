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

package com.nageoffer.ai.ragent.ingestion.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionEvaluatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConditionEvaluator evaluator = new ConditionEvaluator(objectMapper);

    @Test
    @DisplayName("缺失字段参与数值比较返回 false：不得把 null 当作 0 命中 gte 0")
    void missingFieldNeverMatchesNumericComparison() {
        IngestionContext context = IngestionContext.builder().build();

        assertFalse(evaluate(context, "{\"field\":\"rawText.length\",\"operator\":\"gte\",\"value\":0}"));
        assertFalse(evaluate(context, "{\"field\":\"unknownField\",\"operator\":\"gte\",\"value\":0}"));
    }

    @Test
    @DisplayName("非法字符串与 NaN / Infinity 参与数值比较一律 false")
    void nonFiniteNumbersNeverMatch() {
        IngestionContext context = IngestionContext.builder().taskId("abc").build();

        assertFalse(evaluate(context, "{\"field\":\"taskId\",\"operator\":\"gte\",\"value\":0}"));
        assertFalse(evaluate(context, "{\"field\":\"taskId\",\"operator\":\"gt\",\"value\":\"NaN\"}"));
        assertFalse(evaluate(context, "{\"field\":\"taskId\",\"operator\":\"lt\",\"value\":\"Infinity\"}"));
    }

    @Test
    @DisplayName("两侧均为有限数值时按大小正常比较")
    void finiteNumbersCompareNormally() {
        IngestionContext context = contextWithRawText("10");

        assertTrue(evaluate(context, "{\"field\":\"rawText\",\"operator\":\"gt\",\"value\":5}"));
        assertTrue(evaluate(context, "{\"field\":\"rawText\",\"operator\":\"gte\",\"value\":10}"));
        assertTrue(evaluate(context, "{\"field\":\"rawText\",\"operator\":\"lte\",\"value\":10}"));
        assertFalse(evaluate(context, "{\"field\":\"rawText\",\"operator\":\"lt\",\"value\":10}"));
    }

    @Test
    @DisplayName("未知对象键 fail-closed：配置笔误（fields 写成 field）不得放行节点")
    void unknownStructureFailsClosed() {
        IngestionContext context = contextWithRawText("hello");

        assertFalse(evaluate(context, "{\"fields\":\"rawText\",\"operator\":\"exists\"}"));
        assertFalse(evaluate(context, "{\"all\":\"rawText\"}"));
        assertFalse(evaluate(context, "{\"any\":\"rawText\"}"));
        assertFalse(evaluate(context, "{\"field\":\"   \",\"operator\":\"exists\"}"));
        assertFalse(evaluate(context, "42"));
    }

    @Test
    @DisplayName("all / any / not 组合按既有短路语义求值")
    void logicalGroupsKeepSemantics() {
        IngestionContext context = IngestionContext.builder().taskId("task-1").build();

        assertTrue(evaluate(context, "{\"all\":[{\"field\":\"taskId\",\"operator\":\"exists\"}]}"));
        assertFalse(evaluate(context, "{\"all\":[{\"field\":\"taskId\",\"operator\":\"exists\"},"
                + "{\"field\":\"rawText\",\"operator\":\"exists\"}]}"));
        assertTrue(evaluate(context, "{\"any\":[{\"field\":\"rawText\",\"operator\":\"exists\"},"
                + "{\"field\":\"taskId\",\"operator\":\"exists\"}]}"));
        assertTrue(evaluate(context, "{\"not\":{\"field\":\"rawText\",\"operator\":\"exists\"}}"));
        assertTrue(evaluate(context, "{\"all\":[]}"), "空 all 保持恒真语义");
        assertFalse(evaluate(context, "{\"any\":[]}"), "空 any 保持恒假语义");
    }

    @Test
    @DisplayName("条件为空或 true 时放行，false 时跳过")
    void literalConditionsKeepSemantics() {
        IngestionContext context = contextWithRawText("hello");

        assertTrue(evaluator.evaluate(context, null));
        assertTrue(evaluate(context, "true"));
        assertFalse(evaluate(context, "false"));
    }

    private boolean evaluate(IngestionContext context, String conditionJson) {
        try {
            JsonNode condition = objectMapper.readTree(conditionJson);
            return evaluator.evaluate(context, condition);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private IngestionContext contextWithRawText(String rawText) {
        return IngestionContext.builder().rawText(rawText).build();
    }
}

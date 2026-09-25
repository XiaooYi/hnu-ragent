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

package com.nageoffer.ai.ragent.rag.dto;

import java.util.List;

/**
 * 推荐追问生成结果
 *
 * @param status    结局：SUCCESS 有推荐；EMPTY 已生成但无合适追问（负缓存）；FAILED 生成失败（不落库，可重试）
 * @param questions 追问问题列表
 */
public record RecommendedQuestionsPayload(Status status, List<String> questions) {

    public RecommendedQuestionsPayload {
        questions = questions == null ? List.of() : List.copyOf(questions);
    }

    public static RecommendedQuestionsPayload success(List<String> questions) {
        return questions == null || questions.isEmpty()
                ? empty()
                : new RecommendedQuestionsPayload(Status.SUCCESS, questions);
    }

    public static RecommendedQuestionsPayload empty() {
        return new RecommendedQuestionsPayload(Status.EMPTY, List.of());
    }

    public static RecommendedQuestionsPayload failed() {
        return new RecommendedQuestionsPayload(Status.FAILED, List.of());
    }

    public enum Status {
        SUCCESS,
        EMPTY,
        FAILED
    }
}

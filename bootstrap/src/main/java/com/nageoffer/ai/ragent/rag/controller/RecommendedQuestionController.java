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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.dto.RecommendedQuestionsPayload;
import com.nageoffer.ai.ragent.rag.service.RecommendedQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 推荐追问问题接口
 * <p>
 * GET 只读缓存；POST 幂等生成并落库（已有缓存直接返回）
 */
@RestController
@RequiredArgsConstructor
public class RecommendedQuestionController {

    private final RecommendedQuestionService recommendedQuestionService;

    @GetMapping("/conversations/messages/{messageId}/recommended-questions")
    public Result<RecommendedQuestionsPayload> getCached(@PathVariable String messageId) {
        return Results.success(recommendedQuestionService.getCached(messageId, UserContext.getUserId()));
    }

    @PostMapping("/conversations/messages/{messageId}/recommended-questions")
    public Result<RecommendedQuestionsPayload> generate(@PathVariable String messageId) {
        return Results.success(recommendedQuestionService.generate(messageId, UserContext.getUserId()));
    }
}

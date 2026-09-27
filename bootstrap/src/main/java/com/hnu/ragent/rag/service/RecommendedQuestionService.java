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

package com.hnu.ragent.rag.service;

import com.hnu.ragent.rag.dto.RecommendedQuestionsPayload;

/**
 * 推荐追问问题服务
 */
public interface RecommendedQuestionService {

    /**
     * 读取已缓存的推荐问题；未生成时抛业务异常（由前端决定是否触发生成）
     */
    RecommendedQuestionsPayload getCached(String messageId, String userId);

    /**
     * 幂等生成推荐问题：已有缓存直接返回；仅生成失败才允许重试
     */
    RecommendedQuestionsPayload generate(String messageId, String userId);
}

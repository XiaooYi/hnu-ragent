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

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.controller.request.RetrieveDebugRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RetrieveDebugVO;
import com.nageoffer.ai.ragent.rag.service.RAGDebugService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 检索调试控制器
 * <p>
 * 提供独立的检索调试接口，可观察多通道召回、去重、Rerank 等各阶段的中间结果，
 * 用于排查检索质量问题、调参对比和验证通道启用逻辑。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RAGDebugController {

    private final RAGDebugService ragDebugService;

    /**
     * 检索调试接口
     * <p>
     * 对单条 query 执行完整检索链路（多路召回 → 去重 → Rerank → TopK），
     * 返回每个通道的原始结果和每个后处理阶段的变化量。
     *
     * @param request 调试请求参数
     * @return 包含各阶段中间结果的调试响应
     */
    @PostMapping("/rag/v3/debug/retrieve")
    public Result<RetrieveDebugVO> debugRetrieve(@RequestBody RetrieveDebugRequest request) {
        log.info("检索调试请求 - question: {}, topK: {}", request.getQuestion(), request.getTopK());
        RetrieveDebugVO vo = ragDebugService.debugRetrieve(request);
        return Results.success(vo);
    }
}

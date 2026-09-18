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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.rag.controller.request.RetrieveDebugRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RetrieveDebugVO;

/**
 * 检索调试服务
 * <p>
 * 对单条 query 单独执行检索链路，复用现有的 {@code SearchChannel} 和 {@code SearchResultPostProcessor}，
 * 捕获每个通道的原始召回结果和后处理链的阶段变化量。
 * <p>
 * 与生产对话链路的区别：
 * <ul>
 *   <li>不执行 Query 改写、意图识别、对话记忆加载</li>
 *   <li>不调用 LLM 生成回答</li>
 *   <li>同步返回完整中间结果，非 SSE 流式</li>
 * </ul>
 */
public interface RAGDebugService {

    /**
     * 执行检索调试
     *
     * @param request 调试请求参数
     * @return 包含各通道召回结果和后处理阶段变化的完整调试信息
     */
    RetrieveDebugVO debugRetrieve(RetrieveDebugRequest request);
}

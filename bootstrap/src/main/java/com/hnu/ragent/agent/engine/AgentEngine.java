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

package com.hnu.ragent.agent.engine;

import com.hnu.ragent.agent.dto.AgentRequest;
import com.hnu.ragent.agent.dto.AgentRunResult;

/**
 * Agent 引擎
 * <p>
 * 以接口隔离实现：当前默认实现是自研最小 ReAct 循环，日后替换引擎（如引入 AgentScope）时
 * 调用方（Controller / SSE / 记忆模块）不受影响
 */
public interface AgentEngine {

    /**
     * 跑完一轮 Agent 交互
     *
     * @param request 输入问题与会话信息
     * @return 最终回答与过程步骤
     */
    AgentRunResult run(AgentRequest request);
}

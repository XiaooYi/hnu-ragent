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

package com.hnu.ragent.rag.core.retrieve.channel;

/**
 * 一次检索的预算：把「取数深度」与「最终条数」拆成两个数
 *
 * @param contextTopK  最终进入上下文的条数（由 Rerank 截断）
 * @param recallBudget 各通道的取数深度（候选池大小）
 */
public record RetrievalBudget(int contextTopK, int recallBudget) {

    /**
     * 两者相同的退化预算，用于调用方未提供预算时的兜底
     */
    public static RetrievalBudget uniform(int topK) {
        int normalized = Math.max(1, topK);
        return new RetrievalBudget(normalized, normalized);
    }
}

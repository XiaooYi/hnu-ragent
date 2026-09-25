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

package com.nageoffer.ai.ragent.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 检索命中结果
 * <p>
 * 表示一次向量检索或相关性搜索命中的单条记录
 * 包含原始文档片段 主键以及相关性得分
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class RetrievedChunk {

    /**
     * 命中记录的唯一标识
     * 比如向量库中的 primary key 或文档 id
     */
    private String id;

    /**
     * 命中的文本内容
     * 一般是被切分后的文档片段或段落
     */
    private String text;

    /**
     * 命中得分
     * 数值越大表示与查询的相关性越高
     */
    private Float score;

    /**
     * 精排（Rerank）相关度，取值 0~1，未跑过精排时为 {@code null}
     * <p>
     * 不读 {@link #score}：余弦、BM25、RRF 名次派生值都往那里写，读到时分不清是谁写的；
     * 精排跳过节流或降级为 noop 时，留在 {@code score} 里的是上一个写入方的值。
     * 只有真正跑过精排客户端才会写这个字段，证据相关性闸门据此判定
     */
    private Float rerankScore;

    /**
     * 兼容三分量（id, text, score）的构造方式，{@code rerankScore} 置空表示尚未精排
     */
    public RetrievedChunk(String id, String text, Float score) {
        this(id, text, score, null);
    }
}

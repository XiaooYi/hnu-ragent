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

package com.hnu.ragent.framework.convention;

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
     * 所属文档 ID
     * 检索后由元数据富化补齐，未富化时为 {@code null}
     */
    private String docId;

    /**
     * 分块在所属文档中的序号，从 0 开始
     * 检索后由元数据富化补齐，未富化时为 {@code null}
     */
    private Integer chunkIndex;

    /**
     * 所属文档名称，组装上下文时作为文档标题的内部锚点
     * 检索后由元数据富化补齐，未富化时为 {@code null}
     */
    private String docName;

    /**
     * 来源知识库 collection 名
     * 图谱等跨库检索的通道在结果侧判定归属时写入，供下游按库推导意图归属；本地向量 / 关键词检索为 {@code null}
     */
    private String collectionName;

    /**
     * 兼容三分量（id, text, score）的构造方式，{@code rerankScore} 置空表示尚未精排
     */
    public RetrievedChunk(String id, String text, Float score) {
        this(id, text, score, null, null, null, null, null);
    }
}

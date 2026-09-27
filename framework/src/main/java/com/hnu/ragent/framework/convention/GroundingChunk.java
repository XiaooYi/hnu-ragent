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

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 推荐问题 grounding 片段
 * <p>
 * 由检索片段按文档取最高分、按预算截断后得到，随 assistant 消息落库，
 * 供推荐追问生成时 grounding：保证追问落在系统已掌握的证据面内（问得下去、答得出来），
 * 同时与已答内容保持发散
 * <p>
 * 与 {@link SourceRef} 职责分离：后者面向来源面板与预览（摘录 100 字），
 * 本类面向推荐生成 grounding（片段文本更长）。两者都不参与正式回答的提示词上下文
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GroundingChunk {

    /**
     * 文档名称，供生成追问时识别证据所属文档
     */
    private String docName;

    /**
     * 片段文本，作为追问 grounding 的证据内容
     */
    private String text;
}

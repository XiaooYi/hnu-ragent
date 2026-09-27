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

package com.hnu.ragent.rag.core.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CitationMarkupTest {

    @Test
    @DisplayName("剥掉标准行内引用角标，保留正文")
    void stripsStandardCitations() {
        assertEquals("转专业需要满足学分要求。",
                CitationMarkup.strip("转专业需要满足学分要求。[1](#cite-1)"));
        assertEquals("要点A；要点B。",
                CitationMarkup.strip("要点A[1](#cite-1)；要点B[2](#cite-2)。"));
    }

    @Test
    @DisplayName("编号与锚点不一致时同样剥掉，兼容模型偶发错误格式")
    void stripsMismatchedCitations() {
        assertEquals("结论。", CitationMarkup.strip("结论。[1](#cite-2)"));
    }

    @Test
    @DisplayName("普通链接与普通方括号不受影响")
    void keepsOtherMarkdown() {
        assertEquals("见 [选课手册](https://example.test) 与 [1] 说明",
                CitationMarkup.strip("见 [选课手册](https://example.test) 与 [1] 说明"));
    }

    @Test
    @DisplayName("空内容返回空串")
    void handlesBlank() {
        assertEquals("", CitationMarkup.strip(null));
        assertEquals("", CitationMarkup.strip("   "));
    }
}

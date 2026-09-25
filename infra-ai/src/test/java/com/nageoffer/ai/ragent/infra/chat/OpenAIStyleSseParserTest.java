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

package com.nageoffer.ai.ragent.infra.chat;

import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流式分片的正文判定：纯空白不算正文，否则「200 但没输出」会被当成首包成功
 */
class OpenAIStyleSseParserTest {

    private static final Gson GSON = new Gson();

    @Test
    @DisplayName("正常内容被识别为正文")
    void normalContentIsRecognized() {
        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}", GSON, false);

        assertTrue(event.hasContent());
        assertEquals("你好", event.content());
        assertFalse(event.completed());
    }

    @Test
    @DisplayName("空串与纯空白分片都不算正文")
    void blankContentIsNotRecognized() {
        assertFalse(OpenAIStyleSseParser.parseLine(
                "data: {\"choices\":[{\"delta\":{\"content\":\"\"}}]}", GSON, false).hasContent());
        assertFalse(OpenAIStyleSseParser.parseLine(
                "data: {\"choices\":[{\"delta\":{\"content\":\"   \\n\"}}]}", GSON, false).hasContent(),
                "纯空白分片不得算作首包正文");
    }

    @Test
    @DisplayName("无正文的结束帧标记为已完成，用于切换下一个候选")
    void completionWithoutContentIsMarkedCompleted() {
        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}", GSON, false);

        assertFalse(event.hasContent());
        assertTrue(event.completed());
    }

    @Test
    @DisplayName("只有思考内容不算正文")
    void reasoningOnlyIsNotContent() {
        OpenAIStyleSseParser.ParsedEvent event = OpenAIStyleSseParser.parseLine(
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"思考中\"}}]}", GSON, true);

        assertTrue(event.hasReasoning());
        assertFalse(event.hasContent());
    }

    @Test
    @DisplayName("[DONE] 与空行处理")
    void handlesDoneMarkerAndBlankLine() {
        assertTrue(OpenAIStyleSseParser.parseLine("data: [DONE]", GSON, false).completed());
        assertFalse(OpenAIStyleSseParser.parseLine("", GSON, false).hasContent());
    }
}

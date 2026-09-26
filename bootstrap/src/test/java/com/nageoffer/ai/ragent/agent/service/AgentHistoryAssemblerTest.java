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

package com.nageoffer.ai.ragent.agent.service;

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多轮上下文装配：轮次配对、超轮次压摘要、超预算丢更早轮次
 */
class AgentHistoryAssemblerTest {

    private final AgentProperties properties = new AgentProperties();
    private final AgentHistoryAssembler assembler = new AgentHistoryAssembler(properties);

    @Test
    @DisplayName("无历史时返回空串，调用方据此不注入历史段")
    void returnsEmptyWithoutHistory() {
        assertEquals("", assembler.assemble(null));
        assertEquals("", assembler.assemble(List.of()));
        assertEquals("", assembler.assemble(List.of(message("assistant", "没有对应提问的回答"))));
    }

    @Test
    @DisplayName("轮次配对：user 开一轮，assistant 挂上去；未回答的提问也保留")
    void pairsTurns() {
        String history = assembler.assemble(List.of(
                message("user", "转专业需要什么材料"),
                message("assistant", "需要申请表与成绩单"),
                message("user", "那奖学金呢")));

        assertTrue(history.contains("用户：转专业需要什么材料"));
        assertTrue(history.contains("助手：需要申请表与成绩单"));
        assertTrue(history.contains("用户：那奖学金呢"));
        assertTrue(history.contains("（尚未回答）"), "未答完的提问也要带上，模型才知道刚问了什么");
    }

    @Test
    @DisplayName("超过保留轮次时，更早的轮次压成仅问题摘要")
    void compressesOlderTurns() {
        properties.getHistory().setKeepTurns(2);
        properties.getHistory().setMaxChars(4000);

        List<AgentMessageDO> messages = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            messages.add(message("user", "第" + i + "个问题"));
            messages.add(message("assistant", "第" + i + "个回答"));
        }

        String history = assembler.assemble(messages);

        assertTrue(history.contains("更早的对话（仅保留问题）："));
        assertTrue(history.contains("- 第1个问题"));
        assertTrue(history.contains("- 第2个问题"));
        assertFalse(history.contains("助手：第1个回答"), "被压缩的轮次不再带回答");
        assertTrue(history.contains("助手：第3个回答"));
        assertTrue(history.contains("助手：第4个回答"));
    }

    @Test
    @DisplayName("超出字符预算时优先保留最近的轮次")
    void dropsOldestTurnsWhenOverBudget() {
        properties.getHistory().setKeepTurns(10);
        properties.getHistory().setMaxChars(200);

        List<AgentMessageDO> messages = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            messages.add(message("user", "问题" + i + "：" + "内容".repeat(20)));
            messages.add(message("assistant", "回答" + i + "：" + "细节".repeat(20)));
        }

        String history = assembler.assemble(messages);

        assertTrue(history.contains("问题5"), history);
        assertFalse(history.contains("问题1"), "超预算时应先丢更早的轮次");
    }

    @Test
    @DisplayName("单轮就超预算时至少保留该轮提问")
    void keepsQuestionWhenSingleTurnTooLong() {
        properties.getHistory().setMaxChars(100);

        String history = assembler.assemble(List.of(
                message("user", "问".repeat(500)),
                message("assistant", "答".repeat(500))));

        assertTrue(history.contains("用户："));
        assertTrue(history.length() <= 400);
    }

    private AgentMessageDO message(String role, String content) {
        return AgentMessageDO.builder().role(role).content(content).build();
    }
}

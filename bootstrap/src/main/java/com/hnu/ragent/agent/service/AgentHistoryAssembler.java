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

package com.hnu.ragent.agent.service;

import cn.hutool.core.util.StrUtil;
import com.hnu.ragent.agent.config.AgentProperties;
import com.hnu.ragent.agent.dao.entity.AgentMessageDO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 多轮上下文装配
 * <p>
 * 只带「最近若干轮」的完整问答，更早的轮次压成一行摘要（用户问过什么），并整体受字符预算约束：
 * <ul>
 *   <li>不带全量历史：Agent 的历史里含工具观察结果，全量塞进去会很快撑爆上下文；</li>
 *   <li>不丢历史：用户说「刚才那个再展开讲讲」时，模型至少要记得问过什么；</li>
 *   <li>确定性压缩：压缩规则是纯函数（不额外调模型），因此可单测、可复现；需要模型级摘要时再换实现。</li>
 * </ul>
 * 纯逻辑组件，不依赖数据库：由调用方把已查出的消息传进来
 */
@Component
@RequiredArgsConstructor
public class AgentHistoryAssembler {

    private final AgentProperties agentProperties;

    /**
     * 装配历史块；没有历史时返回空串（调用方据此不注入该段）
     */
    public String assemble(List<AgentMessageDO> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        List<Turn> turns = toTurns(messages);
        if (turns.isEmpty()) {
            return "";
        }
        int keepTurns = Math.max(1, agentProperties.getHistory().getKeepTurns());
        int budget = Math.max(200, agentProperties.getHistory().getMaxChars());

        List<Turn> recent = turns.size() > keepTurns
                ? turns.subList(turns.size() - keepTurns, turns.size())
                : turns;
        List<Turn> dropped = turns.size() > keepTurns ? turns.subList(0, turns.size() - keepTurns) : List.of();

        StringBuilder text = new StringBuilder();
        if (!dropped.isEmpty()) {
            text.append("更早的对话（仅保留问题）：\n");
            for (Turn turn : dropped) {
                text.append("- ").append(clip(turn.question(), 60)).append('\n');
            }
        }
        text.append("最近对话：\n");
        // 从最近一轮往前拼，超预算就丢更早的整轮，保证预算内保留的是「最相关」的近期上下文
        List<String> blocks = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            Turn turn = recent.get(i);
            String block = "用户：" + turn.question() + "\n助手：" + clip(turn.answer(), 400);
            int projected = text.length() + blocks.stream().mapToInt(String::length).sum() + block.length();
            if (projected > budget) {
                if (!blocks.isEmpty()) {
                    // 已有更近的轮次可用：这一轮直接丢掉
                    break;
                }
                // 单轮就超预算：至少带上提问（截断），避免模型完全失忆
                blocks.add("用户：" + clip(turn.question(), Math.max(50, budget / 2)));
                break;
            }
            blocks.add(0, block);
        }
        text.append(String.join("\n\n", blocks));
        return text.toString().trim();
    }

    /**
     * 把消息序列配成轮次：user 开一轮；assistant 挂到当前轮
     */
    List<Turn> toTurns(List<AgentMessageDO> messages) {
        List<Turn> turns = new ArrayList<>();
        String pendingQuestion = null;
        for (AgentMessageDO message : messages) {
            if (message == null || StrUtil.isBlank(message.getContent())) {
                continue;
            }
            if ("user".equalsIgnoreCase(message.getRole())) {
                pendingQuestion = message.getContent().trim();
            } else if ("assistant".equalsIgnoreCase(message.getRole()) && pendingQuestion != null) {
                turns.add(new Turn(pendingQuestion, message.getContent().trim()));
                pendingQuestion = null;
            }
        }
        // 最后一轮只有提问（还没答完）：也带上，模型才知道用户刚问了什么
        if (pendingQuestion != null) {
            turns.add(new Turn(pendingQuestion, "（尚未回答）"));
        }
        return turns;
    }

    private String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim().replaceAll("\\s+", " ");
        return trimmed.length() > max ? trimmed.substring(0, max) + "…" : trimmed;
    }

    /**
     * 一轮问答
     */
    record Turn(String question, String answer) {
    }
}

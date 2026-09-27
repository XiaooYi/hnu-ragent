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

package com.hnu.ragent.agent.skill;

import com.hnu.ragent.agent.config.AgentProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能手册：解析、加载、触发词匹配与渲染
 */
class AgentSkillTest {

    private static final String SAMPLE = """
            ---
            name: 知识库问答
            description: 校内制度类问题的处理流程
            triggers: 规定, 政策、流程
            ---
            1. 先检索，再回答。
            """;

    @Test
    @DisplayName("解析头部与正文，触发词支持中英文分隔符")
    void parsesHeaderAndContent() {
        AgentSkill skill = AgentSkillParser.parse(SAMPLE);

        assertEquals("知识库问答", skill.name());
        assertEquals("校内制度类问题的处理流程", skill.description());
        assertEquals(List.of("规定", "政策", "流程"), skill.triggers());
        assertTrue(skill.content().startsWith("1. 先检索"));
        assertTrue(skill.render().contains("【技能：知识库问答】"));
    }

    @Test
    @DisplayName("格式不合法的文件被跳过而不是抛异常")
    void skipsInvalidFiles() {
        assertNull(AgentSkillParser.parse(null));
        assertNull(AgentSkillParser.parse("没有头部的纯文本"));
        assertNull(AgentSkillParser.parse("---\nname: 只有头部\n---\n"));
        assertNull(AgentSkillParser.parse("---\ndescription: 缺少名字\n---\n正文"));
    }

    @Test
    @DisplayName("从目录加载并按触发词匹配，超过上限截断")
    void loadsAndMatches(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("qa.md"), SAMPLE, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("other.md"), """
                ---
                name: 选课助手
                description: 选课相关
                triggers: 选课, 退课
                ---
                先看培养方案。
                """, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("broken.md"), "无效文件", StandardCharsets.UTF_8);

        AgentProperties properties = new AgentProperties();
        FileSystemAgentSkillService service = new FileSystemAgentSkillService(properties,
                "file:" + tempDir.toAbsolutePath().toString().replace("\\", "/") + "/*.md");
        service.load();

        assertEquals(2, service.listSkills().size(), "坏文件被跳过");
        assertEquals(List.of("知识库问答"), service.match("转专业有什么规定", 5).stream()
                .map(AgentSkill::name).toList());
        assertEquals(1, service.match("选课和退课的规定", 1).size(), "上限截断");
        assertTrue(service.match("今天天气如何", 5).isEmpty(), "无触发词时不注入技能");
    }

    @Test
    @DisplayName("关闭技能注入时不返回任何手册")
    void respectsEnabledFlag(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("qa.md"), SAMPLE, StandardCharsets.UTF_8);
        AgentProperties properties = new AgentProperties();
        properties.getSkills().setEnabled(false);
        FileSystemAgentSkillService service = new FileSystemAgentSkillService(properties,
                "file:" + tempDir.toAbsolutePath().toString().replace("\\", "/") + "/*.md");
        service.load();

        assertEquals(1, service.listSkills().size(), "加载不受开关影响，便于前端展示");
        assertTrue(service.match("有什么规定", 5).isEmpty());
    }
}

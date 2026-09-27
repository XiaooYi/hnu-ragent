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

package com.hnu.ragent.agent.controller;

import com.hnu.ragent.agent.controller.vo.AgentMemoryVO;
import com.hnu.ragent.agent.controller.vo.AgentDashboardVO;
import com.hnu.ragent.agent.controller.vo.AgentSkillVO;
import com.hnu.ragent.agent.controller.vo.AgentToolVO;
import com.hnu.ragent.agent.service.AgentMemoryService;
import com.hnu.ragent.agent.service.AgentDashboardService;
import com.hnu.ragent.agent.skill.AgentSkillService;
import com.hnu.ragent.agent.tool.AgentToolCatalog;
import com.hnu.ragent.framework.convention.Result;
import com.hnu.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * Agent 管理台只读视图与记忆维护
 * <p>
 * 只做三件本期真需要的事：看到「Agent 现在能调哪些工具」「加载了哪些技能手册」「记住了我什么」，
 * 以及让用户能删掉记错的记忆。智能体配置编辑、技能在线编辑随 P3 后续
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.agent", name = "enabled", havingValue = "true")
public class AgentAdminController {

    private final AgentToolCatalog agentToolCatalog;
    private final AgentSkillService agentSkillService;
    private final AgentMemoryService agentMemoryService;
    private final AgentDashboardService agentDashboardService;

    /**
     * 当前可用的工具（含读写属性与来源），供管理台核对确认策略是否符合预期
     */
    @GetMapping("/agent/tools")
    public Result<List<AgentToolVO>> tools() {
        List<AgentToolVO> tools = agentToolCatalog.listTools().stream()
                .map(tool -> AgentToolVO.builder()
                        .id(tool.id())
                        .name(tool.name())
                        .description(tool.description())
                        .readOnly(tool.readOnly())
                        .requiresConfirmation(agentToolCatalog.requiresConfirmation(tool.id()))
                        .source(tool.source().name())
                        .parameters(tool.parameters().stream()
                                .map(parameter -> AgentToolVO.Parameter.builder()
                                        .name(parameter.name())
                                        .type(parameter.type())
                                        .description(parameter.description())
                                        .required(parameter.required())
                                        .enumValues(parameter.enumValues())
                                        .build())
                                .toList())
                        .build())
                .toList();
        return Results.success(tools);
    }

    /**
     * 已加载的技能手册（不受 skills.enabled 影响，便于核对手册内容）
     */
    @GetMapping("/agent/skills")
    public Result<List<AgentSkillVO>> skills() {
        List<AgentSkillVO> skills = agentSkillService.listSkills().stream()
                .map(skill -> AgentSkillVO.builder()
                        .name(skill.name())
                        .description(skill.description())
                        .triggers(skill.triggers())
                        .content(skill.content())
                        .build())
                .toList();
        return Results.success(skills);
    }

    /**
     * 运行指标（当前登录用户维度）：会话 / 消息 / 状态分布 / 平均耗时 / 工具使用 / 记忆条数
     */
    @GetMapping("/agent/dashboard")
    public Result<AgentDashboardVO> dashboard(@RequestParam(defaultValue = "7") int days) {
        return Results.success(agentDashboardService.summary(days));
    }

    /**
     * 我的生效记忆
     */
    @GetMapping("/agent/memories")
    public Result<List<AgentMemoryVO>> memories() {
        List<AgentMemoryVO> memories = agentMemoryService.listActive().stream()
                .map(memory -> AgentMemoryVO.builder()
                        .id(memory.getId())
                        .content(memory.getContent())
                        .sourceType(memory.getSourceType())
                        .createTime(memory.getCreateTime())
                        .build())
                .toList();
        return Results.success(memories);
    }

    /**
     * 忘掉一条记忆（失效，不物理删除）
     */
    @DeleteMapping("/agent/memories/{id}")
    public Result<Void> forget(@PathVariable String id) {
        agentMemoryService.invalidate(id);
        return Results.success();
    }
}

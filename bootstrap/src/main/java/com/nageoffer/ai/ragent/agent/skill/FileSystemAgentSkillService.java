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

package com.nageoffer.ai.ragent.agent.skill;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件系统技能手册加载
 * <p>
 * 技能手册是**内容**不是**数据**：随代码版本走、可 review、可回滚，因此用 classpath 下的 markdown 文件承载，
 * 而不是建表（建表还要配管理台与权限，收益不成比例）。需要在线编辑时再迁到数据库
 * <p>
 * 加载失败只记 WARN 并跳过该文件：技能是增强，不该因为一个坏文件让服务起不来
 */
@Slf4j
@Service
public class FileSystemAgentSkillService implements AgentSkillService {

    private final AgentProperties agentProperties;
    private final String resourcePattern;
    private final List<AgentSkill> skills = new ArrayList<>();

    public FileSystemAgentSkillService(AgentProperties agentProperties,
                                       @Value("${ai.agent.skills.location:classpath*:agent/skills/*.md}")
                                       String resourcePattern) {
        this.agentProperties = agentProperties;
        this.resourcePattern = resourcePattern;
    }

    @PostConstruct
    public void load() {
        skills.clear();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            for (Resource resource : resolver.getResources(resourcePattern)) {
                try (InputStream input = resource.getInputStream()) {
                    AgentSkill skill = AgentSkillParser.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                    if (skill == null) {
                        log.warn("技能手册格式不合法，已跳过: {}", resource.getFilename());
                        continue;
                    }
                    skills.add(skill);
                }
            }
        } catch (Exception e) {
            log.warn("技能手册加载失败，本轮不提供任何技能: pattern={}", resourcePattern, e);
        }
        log.info("技能手册加载完成, 共 {} 个", skills.size());
    }

    @Override
    public List<AgentSkill> listSkills() {
        return List.copyOf(skills);
    }

    @Override
    public List<AgentSkill> match(String question, int limit) {
        if (!enabled() || StrUtil.isBlank(question) || skills.isEmpty()) {
            return List.of();
        }
        int maxSkills = limit > 0 ? limit : agentProperties.getSkills().getMaxSkills();
        List<AgentSkill> matched = new ArrayList<>();
        for (AgentSkill skill : skills) {
            if (hit(skill, question)) {
                matched.add(skill);
            }
            if (matched.size() >= maxSkills) {
                break;
            }
        }
        return matched;
    }

    /**
     * 命中判定：触发词包含于问题，或问题直接提到了技能名
     */
    private boolean hit(AgentSkill skill, String question) {
        if (question.contains(skill.name())) {
            return true;
        }
        return skill.triggers().stream().anyMatch(question::contains);
    }

    private boolean enabled() {
        return Boolean.TRUE.equals(agentProperties.getSkills().getEnabled());
    }
}

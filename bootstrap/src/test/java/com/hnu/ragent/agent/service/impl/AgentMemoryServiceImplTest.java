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

package com.hnu.ragent.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hnu.ragent.agent.config.AgentProperties;
import com.hnu.ragent.agent.dao.entity.AgentMemoryDO;
import com.hnu.ragent.agent.dao.mapper.AgentMemoryMapper;
import com.hnu.ragent.framework.context.LoginUser;
import com.hnu.ragent.framework.context.UserContext;
import com.hnu.ragent.framework.convention.ChatRequest;
import com.hnu.ragent.infra.chat.LLMService;
import com.hnu.ragent.infra.enums.Tier;
import com.hnu.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长期记忆：召回排序、抽取解析、去重与取代（失效不物理删除）
 */
class AgentMemoryServiceImplTest {

    private final AgentMemoryMapper memoryMapper = mock(AgentMemoryMapper.class);
    private final LLMService llmService = mock(LLMService.class);
    private final PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
    private final AgentProperties properties = new AgentProperties();

    private AgentMemoryServiceImpl service;

    @BeforeEach
    void setUp() {
        when(promptTemplateLoader.render(anyString(), anyMap())).thenReturn("提示词");
        properties.getMemory().setEnabled(true);
        properties.getMemory().setRecallLimit(2);
        properties.getMemory().setMaxFactsPerTurn(2);
        service = new AgentMemoryServiceImpl(memoryMapper, properties, llmService,
                promptTemplateLoader, new ObjectMapper());
        UserContext.set(LoginUser.builder().userId("u-1").username("tester").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("关闭时不召回也不抽取")
    void disabledMeansNoop() {
        properties.getMemory().setEnabled(false);

        assertTrue(service.recall("转专业").isEmpty());
        assertEquals(0, service.remember("问题", "回答"));
        verify(llmService, never()).chat(any(ChatRequest.class), eq(Tier.FAST));
    }

    @Test
    @DisplayName("召回按字符重合度排序并按上限截断")
    void recallRanksByOverlap() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(
                memory("m-1", "用户是 2024 级本科生", new Date(1000)),
                memory("m-2", "用户偏好中文回答", new Date(2000)),
                memory("m-3", "用户只关心本校规定", new Date(3000))
        ));

        List<String> recalled = service.recall("本校转专业规定");

        assertEquals(2, recalled.size());
        assertEquals("用户只关心本校规定", recalled.get(0));
    }

    @Test
    @DisplayName("抽取解析：忽略无内容项、类型归一到白名单、条数封顶")
    void parsesFacts() {
        List<AgentMemoryDO> facts = service.parseFacts("""
                {"facts":[
                  {"content":"用户是 2024 级本科生","type":"FACT"},
                  {"content":"","type":"FACT"},
                  {"content":"用户偏好中文回答","type":"偏好"},
                  {"content":"第三条不该进入","type":"FACT"}
                ]}
                """);

        assertEquals(2, facts.size(), "max-facts-per-turn=2");
        assertEquals("FACT", facts.get(0).getSourceType());
        assertEquals("FACT", facts.get(1).getSourceType(), "未知类型归一到 FACT");
    }

    @Test
    @DisplayName("非法 JSON 不写任何记忆")
    void invalidJsonWritesNothing() {
        when(memoryMapper.selectList(any())).thenReturn(List.of());
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("我不确定");

        assertEquals(0, service.remember("问题", "回答"));
        verify(memoryMapper, never()).insert(any(AgentMemoryDO.class));
    }

    @Test
    @DisplayName("相同事实不重复写")
    void skipsDuplicatedFact() {
        when(memoryMapper.selectList(any())).thenReturn(List.of(memory("m-1", "用户是 2024 级本科生", new Date())));
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn(
                "{\"facts\":[{\"content\":\"用户是 2024 级本科生\",\"type\":\"FACT\"}]}");

        assertEquals(0, service.remember("我是 2024 级本科生", "好的"));
        verify(memoryMapper, never()).insert(any(AgentMemoryDO.class));
    }

    @Test
    @DisplayName("新事实取代旧记忆：旧记忆失效并指向新记忆，不物理删除")
    void supersedesOldMemory() {
        AgentMemoryDO old = memory("m-1", "用户是 2023 级本科生", new Date());
        when(memoryMapper.selectList(any())).thenReturn(List.of(old));
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("""
                {"facts":[{"content":"用户是 2024 级本科生","type":"FACT","replaces":"用户是 2023 级本科生"}]}
                """);
        when(memoryMapper.insert(any(AgentMemoryDO.class))).thenAnswer(invocation -> {
            AgentMemoryDO inserted = invocation.getArgument(0);
            inserted.setId("m-2");
            return 1;
        });

        assertEquals(1, service.remember("我已升到 2024 级", "好的"));

        ArgumentCaptor<AgentMemoryDO> insertedCaptor = ArgumentCaptor.forClass(AgentMemoryDO.class);
        verify(memoryMapper).insert(insertedCaptor.capture());
        assertNull(insertedCaptor.getValue().getSupersededBy(), "落库的新记忆不再持有临时占位值");
        assertEquals("u-1", insertedCaptor.getValue().getUserId());

        ArgumentCaptor<AgentMemoryDO> captor = ArgumentCaptor.forClass(AgentMemoryDO.class);
        verify(memoryMapper).updateById(captor.capture());
        AgentMemoryDO invalidated = captor.getValue();
        assertEquals("m-1", invalidated.getId());
        assertNotNull(invalidated.getInvalidAt(), "旧记忆应被置为失效");
        assertEquals("m-2", invalidated.getSupersededBy());
    }

    @Test
    @DisplayName("手工失效只作用于本人的生效记忆")
    void invalidatesOwnActiveMemory() {
        when(memoryMapper.selectById("m-1")).thenReturn(memory("m-1", "用户偏好中文回答", new Date()));

        service.invalidate("m-1");

        ArgumentCaptor<AgentMemoryDO> captor = ArgumentCaptor.forClass(AgentMemoryDO.class);
        verify(memoryMapper).updateById(captor.capture());
        assertNotNull(captor.getValue().getInvalidAt());

        when(memoryMapper.selectById("m-2")).thenReturn(AgentMemoryDO.builder()
                .id("m-2").userId("other").content("别人的记忆").sourceType("FACT").createTime(new Date()).build());
        service.invalidate("m-2");
        verify(memoryMapper, times(1)).updateById(any(AgentMemoryDO.class));
    }

    private AgentMemoryDO memory(String id, String content, Date createTime) {
        return AgentMemoryDO.builder()
                .id(id)
                .userId("u-1")
                .content(content)
                .sourceType("FACT")
                .createTime(createTime)
                .build();
    }
}

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

package com.hnu.ragent.mcp.executor;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共用结果封装：身份读取与回绝、异常文案分级、取参与日期解析
 */
@DisplayName("MCP 工具结果封装")
class McpToolResultsTest {

    @Test
    @DisplayName("身份读取：_meta 缺失 / 空值 / 空白都视为没有身份")
    void readsIdentityFromMeta() {
        assertNull(McpToolResults.userId(null));
        assertNull(McpToolResults.userId(request(Map.of(), Map.of())));
        assertNull(McpToolResults.userId(request(Map.of(McpToolResults.META_USER_ID, "  "), Map.of())));
        assertEquals("u-1", McpToolResults.userId(request(Map.of(McpToolResults.META_USER_ID, " u-1 "), Map.of())));
    }

    @Test
    @DisplayName("缺身份时明确回绝，不降级成按 null 查询")
    void identityRequiredIsExplicit() {
        CallToolResult result = McpToolResults.identityRequired("asset_query");

        assertTrue(result.isError());
        assertTrue(text(result).contains("没有执行"));
        assertFalse(text(result).contains("重新登录"), "文案不应把排查引到登录态上");
    }

    @Test
    @DisplayName("异常文案分级：McpToolException 可回显，其它异常只给统一文案")
    void failureMessageGranularity() {
        CallToolResult explicit = McpToolResults.failure("下单", new McpToolException("库存不足"));
        assertTrue(explicit.isError());
        assertEquals("下单失败: 库存不足", text(explicit));

        CallToolResult hidden = McpToolResults.failure("下单",
                new IllegalStateException("duplicate key value violates unique constraint \"t_order\""));
        assertTrue(hidden.isError());
        assertEquals("下单失败，请稍后重试", text(hidden));
        assertFalse(text(hidden).contains("t_order"), "底层异常原文不得交给模型");
    }

    @Test
    @DisplayName("取参与日期解析")
    void argsAndDateParsing() {
        assertEquals(Map.of("query", "x"), McpToolResults.args(request(Map.of(), Map.of("query", "x"))));
        assertTrue(McpToolResults.args(request(Map.of(), null)).isEmpty(), "arguments 为 null 时返回空表");

        assertEquals(LocalDate.of(2026, 9, 26), McpToolResults.parseDate(" 2026-09-26 "));
        assertNull(McpToolResults.parseDate(""));
        assertNull(McpToolResults.parseDate("2026/09/26"));
        assertNull(McpToolResults.parseDate(null));
    }

    private CallToolRequest request(Map<String, Object> meta, Map<String, Object> args) {
        Map<String, Object> request = new HashMap<>();
        request.put("name", "tool");
        request.put("arguments", args);
        return new CallToolRequest("tool", args, meta);
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }
}

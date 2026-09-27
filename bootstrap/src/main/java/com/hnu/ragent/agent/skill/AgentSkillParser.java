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

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能手册解析
 * <p>
 * 格式刻意保持极简（不引入 YAML 依赖）：文件以 {@code ---} 包围的头部开始，头部是 {@code key: value}，
 * 其余为手册正文：
 * <pre>
 * ---
 * name: 转专业咨询
 * description: 处理转专业类多步咨询
 * triggers: 转专业, 换专业
 * ---
 * 1. 先查转专业条件 …
 * </pre>
 */
public final class AgentSkillParser {

    private static final String DELIMITER = "---";

    private AgentSkillParser() {
    }

    /**
     * 解析手册内容；缺少 name 或正文时返回 null（该文件被跳过）
     */
    public static AgentSkill parse(String raw) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        String normalized = raw.replace("\r\n", "\n").trim();
        if (!normalized.startsWith(DELIMITER)) {
            return null;
        }
        int headerEnd = normalized.indexOf('\n' + DELIMITER);
        if (headerEnd < 0) {
            return null;
        }
        Map<String, String> header = parseHeader(normalized.substring(DELIMITER.length(), headerEnd));
        String content = normalized.substring(headerEnd + DELIMITER.length() + 1).trim();
        String name = StrUtil.trimToNull(header.get("name"));
        if (name == null || content.isEmpty()) {
            return null;
        }
        return new AgentSkill(name,
                StrUtil.blankToDefault(header.get("description"), name),
                parseTriggers(header.get("triggers")),
                content);
    }

    private static Map<String, String> parseHeader(String headerBlock) {
        Map<String, String> header = new LinkedHashMap<>();
        for (String line : headerBlock.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            header.put(trimmed.substring(0, colon).trim().toLowerCase(), trimmed.substring(colon + 1).trim());
        }
        return header;
    }

    /**
     * 触发词分隔：中英文逗号、顿号、分号都支持
     */
    private static List<String> parseTriggers(String raw) {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        List<String> triggers = new ArrayList<>();
        for (String part : raw.split("[,，、;；]")) {
            String trigger = part.trim();
            if (!trigger.isEmpty() && !triggers.contains(trigger)) {
                triggers.add(trigger);
            }
        }
        return triggers;
    }
}

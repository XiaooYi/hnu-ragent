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

package com.nageoffer.ai.ragent.rag.core.mcp;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * MCP 调用元信息（{@code _meta}）键约定
 * <p>
 * 与 mcp-server 侧的 {@code McpToolResults.META_USER_ID} 必须保持一致：两个进程各自独立启动、
 * 不共享依赖，只能各存一份常量。修改键名时两处需同步
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class McpCallMeta {

    /**
     * 调用方透传的登录用户 ID
     */
    public static final String USER_ID = "com.nageoffer.ragent/userId";
}

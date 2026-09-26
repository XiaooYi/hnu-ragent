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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceNodeMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.RagTraceRunMapper;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import com.nageoffer.ai.ragent.rag.trace.LangfuseReporter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * RAG Trace 记录服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagTraceRecordServiceImpl implements RagTraceRecordService {

    private final RagTraceRunMapper runMapper;
    private final RagTraceNodeMapper nodeMapper;
    /**
     * 惰性解析：LangFuse 未启用时容器里没有该 bean，链路不受影响
     */
    private final ObjectProvider<LangfuseReporter> langfuseReporterProvider;

    @Override
    public void startRun(RagTraceRunDO run) {
        runMapper.insert(run);
    }

    @Override
    public void finishRun(String traceId, String status, String errorMessage, Date endTime, long durationMs) {
        RagTraceRunDO update = RagTraceRunDO.builder()
                .status(status)
                .errorMessage(errorMessage)
                .endTime(endTime)
                .durationMs(durationMs)
                .build();
        runMapper.update(update, Wrappers.lambdaUpdate(RagTraceRunDO.class)
                .eq(RagTraceRunDO::getTraceId, traceId));
        reportToLangfuse(traceId, status, errorMessage, endTime, durationMs);
    }

    /**
     * 往 LangFuse 同步一份运行与节点（best-effort，失败只记日志）
     */
    private void reportToLangfuse(String traceId, String status, String errorMessage, Date endTime, long durationMs) {
        LangfuseReporter reporter = langfuseReporterProvider.getIfAvailable();
        if (reporter == null) {
            return;
        }
        try {
            RagTraceRunDO run = runMapper.selectOne(Wrappers.lambdaQuery(RagTraceRunDO.class)
                    .eq(RagTraceRunDO::getTraceId, traceId)
                    .last("LIMIT 1"));
            if (run == null) {
                return;
            }
            reporter.reportRagRun(run, nodeMapper.selectList(Wrappers.lambdaQuery(RagTraceNodeDO.class)
                    .eq(RagTraceNodeDO::getTraceId, traceId)));
        } catch (Exception e) {
            log.warn("LangFuse 上报准备失败（不影响业务）, traceId={}, status={}, durationMs={}",
                    traceId, status, durationMs, e);
        }
    }

    @Override
    public void startNode(RagTraceNodeDO node) {
        nodeMapper.insert(node);
    }

    @Override
    public void finishNode(String traceId, String nodeId, String status, String errorMessage, Date endTime, long durationMs) {
        RagTraceNodeDO update = RagTraceNodeDO.builder()
                .status(status)
                .errorMessage(errorMessage)
                .endTime(endTime)
                .durationMs(durationMs)
                .build();
        nodeMapper.update(update, Wrappers.lambdaUpdate(RagTraceNodeDO.class)
                .eq(RagTraceNodeDO::getTraceId, traceId)
                .eq(RagTraceNodeDO::getNodeId, nodeId));
    }
}

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

package com.nageoffer.ai.ragent.audit.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.audit.annotation.BizChangeLog;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.dao.entity.BizChangeLogDO;
import com.nageoffer.ai.ragent.audit.service.BizChangeLogRecordService;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogTemplateEvaluator;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审计切面：成功/失败留痕、动态操作类型、降级不影响业务、显式跳过
 */
class BizChangeLogAspectTest {

    private final BizChangeLogContext context = new BizChangeLogContext(new ObjectMapper());
    private final BizChangeLogTemplateEvaluator evaluator = new BizChangeLogTemplateEvaluator();
    private final BizChangeLogRecordService recordService = mock(BizChangeLogRecordService.class);

    private BizChangeLogAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new BizChangeLogAspect(context, evaluator, recordService);
        UserContext.set(LoginUser.builder().userId("1001").username("zhangsan").role("admin").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        context.clear();
    }

    @Test
    @DisplayName("成功时记录操作人、渲染描述与快照差异")
    void recordsSuccessWithSnapshot() throws Throwable {
        Method method = Samples.class.getMethod("update", SampleRequest.class);
        ProceedingJoinPoint joinPoint = joinPoint(method, new Object[]{new SampleRequest("kb-1", "新名称")});
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            context.put("kb-1", Map.of("name", "旧名称"), Map.of("name", "新名称"));
            return null;
        });

        Object result = aspect.around(joinPoint, annotation(
                BizChangeBizType.KNOWLEDGE_BASE, BizChangeOperationType.UPDATE, "更新知识库：{{#requestParam.name}}"));

        assertNull(result);
        BizChangeLogDO record = captureOne();
        assertEquals("KNOWLEDGE_BASE", record.getBizType());
        assertEquals("UPDATE", record.getOperationType());
        assertEquals("kb-1", record.getBizId());
        assertEquals("更新知识库：新名称", record.getActionDesc());
        assertTrue(record.getSuccess());
        assertNull(record.getErrorMessage());
        assertEquals("1001", record.getOperatorId());
        assertEquals("zhangsan", record.getOperatorName());
        assertEquals("admin", record.getOperatorRole());
        assertEquals(Samples.class.getName(), record.getClassName());
        assertEquals("update", record.getMethodName());
        assertNotNull(record.getCreateTime());

        assertTrue(record.getBeforeSnapshot().contains("旧名称"), record.getBeforeSnapshot());
        assertTrue(record.getAfterSnapshot().contains("新名称"), record.getAfterSnapshot());
        assertTrue(record.getChangeDiff().contains("\"field\":\"/name\""), record.getChangeDiff());
    }

    @Test
    @DisplayName("业务抛异常：记录失败原因并把异常原样抛出")
    void recordsFailureAndRethrows() throws Throwable {
        Method method = Samples.class.getMethod("update", SampleRequest.class);
        ProceedingJoinPoint joinPoint = joinPoint(method, new Object[]{new SampleRequest("kb-2", "x")});
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("库名已存在"));

        BizChangeLog annotation = annotation(
                BizChangeBizType.KNOWLEDGE_BASE, BizChangeOperationType.UPDATE, "更新知识库：{{#requestParam.id}}",
                "#requestParam.id");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> aspect.around(joinPoint, annotation));

        assertEquals("库名已存在", thrown.getMessage());
        BizChangeLogDO record = captureOne();
        assertFalse(record.getSuccess());
        assertEquals("库名已存在", record.getErrorMessage());
        assertEquals("操作失败：库名已存在", record.getActionDesc());
        assertEquals("kb-2", record.getBizId());
    }

    @Test
    @DisplayName("同一方法记录多条变更时逐条落库")
    void recordsEverySnapshot() throws Throwable {
        Method method = Samples.class.getMethod("batch", List.class);
        ProceedingJoinPoint joinPoint = joinPoint(method, new Object[]{List.of("n1", "n2")});
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            context.put("n1", Map.of("enabled", 0), Map.of("enabled", 1));
            context.put("n2", Map.of("enabled", 0), Map.of("enabled", 1));
            return null;
        });

        aspect.around(joinPoint, annotation(BizChangeBizType.INTENT_TREE, BizChangeOperationType.ENABLE, "批量启用"));

        ArgumentCaptor<BizChangeLogDO> captor = ArgumentCaptor.forClass(BizChangeLogDO.class);
        verify(recordService, times(2)).record(captor.capture());
        assertEquals(List.of("n1", "n2"), captor.getAllValues().stream().map(BizChangeLogDO::getBizId).toList());
    }

    @Test
    @DisplayName("没有快照也要留痕，操作类型支持按入参区分")
    void recordsWithoutSnapshotAndRendersOperationType() throws Throwable {
        Method method = Samples.class.getMethod("enable", String.class, boolean.class);
        ProceedingJoinPoint joinPoint = joinPoint(method, new Object[]{"doc-1", false});
        when(joinPoint.proceed()).thenReturn("done");

        BizChangeLog annotation = mock(BizChangeLog.class);
        when(annotation.bizType()).thenReturn(BizChangeBizType.KNOWLEDGE_DOCUMENT);
        when(annotation.operationType()).thenReturn("{{#enabled ? 'ENABLE' : 'DISABLE'}}");
        when(annotation.success()).thenReturn("{{#enabled ? '启用' : '禁用'}}文档：{{#docId}}");
        when(annotation.fail()).thenReturn("");
        when(annotation.bizId()).thenReturn("#docId");

        assertEquals("done", aspect.around(joinPoint, annotation));

        BizChangeLogDO record = captureOne();
        assertEquals("DISABLE", record.getOperationType());
        assertEquals("禁用文档：doc-1", record.getActionDesc());
        assertEquals("doc-1", record.getBizId());
        assertNull(record.getChangeDiff());
    }

    @Test
    @DisplayName("业务显式 skip 时不落库")
    void skipSkipsRecording() throws Throwable {
        Method method = Samples.class.getMethod("enable", String.class, boolean.class);
        ProceedingJoinPoint joinPoint = joinPoint(method, new Object[]{"doc-1", true});
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            context.skip();
            return "done";
        });

        assertEquals("done", aspect.around(joinPoint,
                annotation(BizChangeBizType.KNOWLEDGE_DOCUMENT, BizChangeOperationType.ENABLE, "启用文档")));

        verify(recordService, never()).record(any());
    }

    @Test
    @DisplayName("审计落库失败不影响业务返回值")
    void auditFailureDoesNotBreakBusiness() throws Throwable {
        Method method = Samples.class.getMethod("update", SampleRequest.class);
        ProceedingJoinPoint joinPoint = joinPoint(method, new Object[]{new SampleRequest("kb-3", "x")});
        when(joinPoint.proceed()).thenReturn("business-result");
        doThrow(new IllegalStateException("审计库不可用")).when(recordService).record(any());

        Object result = aspect.around(joinPoint,
                annotation(BizChangeBizType.KNOWLEDGE_BASE, BizChangeOperationType.UPDATE, "更新知识库"));

        assertSame("business-result", result);
        verify(recordService).record(any());
    }

    private BizChangeLog annotation(String bizType, String operationType, String success) {
        return annotation(bizType, operationType, success, "");
    }

    private BizChangeLog annotation(String bizType, String operationType, String success, String bizId) {
        BizChangeLog annotation = mock(BizChangeLog.class);
        when(annotation.bizType()).thenReturn(bizType);
        when(annotation.operationType()).thenReturn(operationType);
        when(annotation.success()).thenReturn(success);
        when(annotation.fail()).thenReturn("操作失败：{{#_errorMsg}}");
        when(annotation.bizId()).thenReturn(bizId);
        return annotation;
    }

    private ProceedingJoinPoint joinPoint(Method method, Object[] args) {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(args);
        return joinPoint;
    }

    private BizChangeLogDO captureOne() {
        ArgumentCaptor<BizChangeLogDO> captor = ArgumentCaptor.forClass(BizChangeLogDO.class);
        verify(recordService).record(captor.capture());
        return captor.getValue();
    }

    /**
     * 供反射取 Method 的样例目标方法，参数名与真实业务方法一致
     */
    @SuppressWarnings("unused")
    static class Samples {

        public void update(SampleRequest requestParam) {
        }

        public void batch(List<String> ids) {
        }

        public String enable(String docId, boolean enabled) {
            return "done";
        }
    }

    static class SampleRequest {

        private final String id;
        private final String name;

        SampleRequest(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }
}

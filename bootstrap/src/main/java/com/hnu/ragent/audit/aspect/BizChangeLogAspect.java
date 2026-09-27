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

package com.hnu.ragent.audit.aspect;

import com.fasterxml.jackson.databind.JsonNode;
import com.hnu.ragent.audit.annotation.BizChangeLog;
import com.hnu.ragent.audit.dao.entity.BizChangeLogDO;
import com.hnu.ragent.audit.service.BizChangeLogRecordService;
import com.hnu.ragent.audit.support.BizChangeLogContext;
import com.hnu.ragent.audit.support.BizChangeLogContext.BizChangeSnapshot;
import com.hnu.ragent.audit.support.BizChangeLogTemplateEvaluator;
import com.hnu.ragent.framework.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Date;

/**
 * 审计埋点切面
 * <p>
 * 方法正常返回记为成功、抛异常记为失败（异常照常向上抛），两者都在独立新事务中落库。
 * 审计自身的任何异常只记日志，不影响业务返回值与异常。
 * <p>
 * 不处理嵌套埋点：内层方法已消费并清理上下文，外层方法会按「无快照」记一条操作流水。
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
public class BizChangeLogAspect {

    private static final String UNKNOWN_BIZ_ID = "UNKNOWN";

    private final BizChangeLogContext bizChangeLogContext;
    private final BizChangeLogTemplateEvaluator templateEvaluator;
    private final BizChangeLogRecordService bizChangeLogRecordService;

    @Around("@annotation(bizChangeLog)")
    public Object around(ProceedingJoinPoint joinPoint, BizChangeLog bizChangeLog) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Object[] args = joinPoint.getArgs();
        try {
            Object result = joinPoint.proceed();
            recordQuietly(bizChangeLog, method, args, true, null);
            return result;
        } catch (Throwable throwable) {
            recordQuietly(bizChangeLog, method, args, false, throwable.getMessage());
            throw throwable;
        }
    }

    /**
     * 落库审计记录；任何异常只记日志
     */
    private void recordQuietly(BizChangeLog annotation, Method method, Object[] args,
                               boolean success, String errorMsg) {
        try {
            List<BizChangeSnapshot> snapshots = bizChangeLogContext.consume();
            if (snapshots == null) {
                // 业务显式跳过（条件式埋点）
                return;
            }
            if (snapshots.isEmpty()) {
                // 没有快照也要留痕（例如批量操作或失败在写入之前）
                bizChangeLogRecordService.record(buildRecord(annotation, method, args, success, errorMsg, null));
                return;
            }
            for (BizChangeSnapshot snapshot : snapshots) {
                bizChangeLogRecordService.record(buildRecord(annotation, method, args, success, errorMsg, snapshot));
            }
        } catch (Throwable t) {
            log.warn("记录业务变更审计失败, bizType={}, method={}#{}", annotation.bizType(),
                    method.getDeclaringClass().getSimpleName(), method.getName(), t);
        }
    }

    private BizChangeLogDO buildRecord(BizChangeLog annotation, Method method, Object[] args,
                                       boolean success, String errorMsg, BizChangeSnapshot snapshot) {
        EvaluationContext context = templateEvaluator.buildContext(method, args, errorMsg);
        String template = success ? annotation.success() : annotation.fail();
        if (!success && !StringUtils.hasText(template)) {
            template = annotation.success() + "（失败：{{#_errorMsg}}）";
        }
        String actionDesc = templateEvaluator.render(template, method, args, errorMsg);
        String operationType = templateEvaluator.render(annotation.operationType(), method, args, errorMsg);
        if (!StringUtils.hasText(operationType)) {
            operationType = annotation.operationType();
        }
        HttpServletRequest request = currentRequest();

        return BizChangeLogDO.builder()
                .bizType(limit(annotation.bizType(), 64))
                .bizId(limit(resolveBizId(annotation, context, snapshot), 64))
                .operationType(limit(operationType, 32))
                .actionDesc(limit(actionDesc, 512))
                .beforeSnapshot(jsonNodeToString(snapshot == null ? null : snapshot.beforeSnapshot()))
                .afterSnapshot(jsonNodeToString(snapshot == null ? null : snapshot.afterSnapshot()))
                .changeDiff(jsonNodeToString(snapshot == null ? null : snapshot.changeDiff()))
                .operatorId(limit(resolveOperatorId(), 64))
                .operatorName(limit(UserContext.getUsername(), 128))
                .operatorRole(limit(UserContext.getRole(), 64))
                .success(success)
                .errorMessage(success ? null : errorMsg)
                .className(limit(method.getDeclaringClass().getName(), 255))
                .methodName(limit(method.getName(), 255))
                .ip(limit(resolveIp(request), 64))
                .userAgent(limit(request == null ? null : request.getHeader("User-Agent"), 512))
                .createTime(new Date())
                .build();
    }

    /**
     * 业务主键优先取上下文快照（如上传文档时的文档 id），其次取注解表达式，最后记为 UNKNOWN
     */
    private String resolveBizId(BizChangeLog annotation, EvaluationContext context, BizChangeSnapshot snapshot) {
        if (snapshot != null && StringUtils.hasText(snapshot.bizId())) {
            return snapshot.bizId();
        }
        if (StringUtils.hasText(annotation.bizId())) {
            String evaluated = templateEvaluator.evaluate(annotation.bizId(), context);
            if (StringUtils.hasText(evaluated)) {
                return evaluated;
            }
        }
        return UNKNOWN_BIZ_ID;
    }

    private String resolveOperatorId() {
        String userId = UserContext.getUserId();
        if (StringUtils.hasText(userId)) {
            return userId;
        }
        String username = UserContext.getUsername();
        return StringUtils.hasText(username) ? username : "SYSTEM";
    }

    private String jsonNodeToString(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.toString();
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String resolveIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwardedFor = firstHeaderValue(request.getHeader("X-Forwarded-For"));
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor;
        }
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.hasText(realIp) ? realIp : request.getRemoteAddr();
    }

    private String firstHeaderValue(String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return null;
        }
        int commaIndex = headerValue.indexOf(',');
        return commaIndex >= 0 ? headerValue.substring(0, commaIndex).trim() : headerValue.trim();
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}

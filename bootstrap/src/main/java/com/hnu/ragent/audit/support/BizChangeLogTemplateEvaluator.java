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

package com.hnu.ragent.audit.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计描述模板求值：把 {@code "更新知识库：{{#requestParam.id}}"} 渲染成具体文案
 * <p>
 * 模板只认 {@code {{表达式}}} 片段，表达式用 Spring SpEL 求值，变量是方法参数名（如 {@code #requestParam}）
 * 与切面注入的 {@code #_errorMsg}。求值失败不抛异常，退回原始模板，避免审计把业务带崩。
 */
@Slf4j
@Component
public class BizChangeLogTemplateEvaluator {

    private static final String PLACEHOLDER_PREFIX = "{{";
    private static final String PLACEHOLDER_SUFFIX = "}}";

    private final SpelExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 渲染模板；模板为空时返回 null
     */
    public String render(String template, Method method, Object[] args, String errorMsg) {
        if (template == null || template.isEmpty()) {
            return null;
        }
        Map<String, Object> variables = resolveVariables(method, args);
        variables.put("_errorMsg", errorMsg);
        EvaluationContext context = new StandardEvaluationContext();
        variables.forEach(context::setVariable);

        StringBuilder rendered = new StringBuilder();
        int cursor = 0;
        while (cursor < template.length()) {
            int start = template.indexOf(PLACEHOLDER_PREFIX, cursor);
            if (start < 0) {
                rendered.append(template, cursor, template.length());
                break;
            }
            int end = template.indexOf(PLACEHOLDER_SUFFIX, start + PLACEHOLDER_PREFIX.length());
            if (end < 0) {
                rendered.append(template, cursor, template.length());
                break;
            }
            rendered.append(template, cursor, start);
            String expression = template.substring(start + PLACEHOLDER_PREFIX.length(), end).trim();
            rendered.append(evaluate(expression, context));
            cursor = end + PLACEHOLDER_SUFFIX.length();
        }
        return rendered.toString();
    }

    /**
     * 求值单个表达式；失败返回空串，并把原因留给日志
     */
    public String evaluate(String expression, EvaluationContext context) {
        if (expression.isEmpty()) {
            return "";
        }
        try {
            Expression parsed = parser.parseExpression(expression);
            Object value = parsed.getValue(context);
            return value == null ? "" : String.valueOf(value);
        } catch (Exception e) {
            log.warn("审计描述表达式求值失败, expression={}", expression, e);
            return "";
        }
    }

    /**
     * 构建求值上下文：方法参数名 → 参数值
     */
    public EvaluationContext buildContext(Method method, Object[] args, String errorMsg) {
        StandardEvaluationContext context = new StandardEvaluationContext();
        resolveVariables(method, args).forEach(context::setVariable);
        context.setVariable("_errorMsg", errorMsg);
        return context;
    }

    private Map<String, Object> resolveVariables(Method method, Object[] args) {
        Map<String, Object> variables = new LinkedHashMap<>();
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        if (parameterNames == null) {
            return variables;
        }
        for (int i = 0; i < parameterNames.length; i++) {
            variables.put(parameterNames[i], args != null && i < args.length ? args[i] : null);
        }
        return variables;
    }
}

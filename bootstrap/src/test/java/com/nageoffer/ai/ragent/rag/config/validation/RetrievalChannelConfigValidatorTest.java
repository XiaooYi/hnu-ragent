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

package com.nageoffer.ai.ragent.rag.config.validation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalChannelConfigValidatorTest {

    private static final String TYPE_KEY = "rag.keyword.type";
    private static final String ENABLED_KEY = "rag.search.channels.keyword.enabled";

    @Test
    void reportsViolationWhenBackendOffButChannelEnabled() {
        List<RetrievalChannelConfigValidator.Violation> violations = validate("none", true);

        assertEquals(1, violations.size());
        RetrievalChannelConfigValidator.Violation violation = violations.get(0);
        assertEquals("rag.keyword.type", violation.typeKey());
        assertEquals("es", violation.requiredType());
        assertEquals(ENABLED_KEY, violation.enabledKey());
        assertTrue(violation.enableHint().contains("rag.keyword.es"));
    }

    @Test
    void reportsViolationWhenTypeMissingButChannelEnabled() {
        List<RetrievalChannelConfigValidator.Violation> violations = validate(null, true);

        assertEquals(1, violations.size());
        assertEquals("", violations.get(0).actualType());
    }

    @Test
    void acceptsBackendOnAndChannelEnabled() {
        assertTrue(validate("es", true).isEmpty());
    }

    @Test
    void acceptsBackendOnButChannelDisabled() {
        assertTrue(validate("es", false).isEmpty());
    }

    @Test
    void acceptsBackendOffAndChannelDisabled() {
        assertTrue(validate("none", false).isEmpty());
    }

    @Test
    void typeComparisonIsCaseAndWhitespaceInsensitive() {
        assertTrue(validate(" ES ", true).isEmpty());
    }

    @Test
    void environmentPostProcessorFailsFastOnContradiction() {
        RetrievalConfigEnvironmentPostProcessor processor = new RetrievalConfigEnvironmentPostProcessor();
        MockEnvironment environment = new MockEnvironment()
                .withProperty(TYPE_KEY, "none")
                .withProperty(ENABLED_KEY, "true");

        assertThrows(RetrievalConfigException.class,
                () -> processor.postProcessEnvironment(environment, new SpringApplication()));
        assertEquals(Ordered.LOWEST_PRECEDENCE, processor.getOrder());
    }

    @Test
    void environmentPostProcessorPassesOnConsistentConfig() {
        RetrievalConfigEnvironmentPostProcessor processor = new RetrievalConfigEnvironmentPostProcessor();
        MockEnvironment environment = new MockEnvironment()
                .withProperty(TYPE_KEY, "none")
                .withProperty(ENABLED_KEY, "false");

        assertDoesNotThrow(() -> processor.postProcessEnvironment(environment, new SpringApplication()));
    }

    private List<RetrievalChannelConfigValidator.Violation> validate(String type, boolean enabled) {
        Map<String, String> values = new HashMap<>();
        if (type != null) {
            values.put(TYPE_KEY, type);
        }
        values.put(ENABLED_KEY, String.valueOf(enabled));
        return RetrievalChannelConfigValidator.validate(
                values::get,
                key -> Boolean.parseBoolean(values.get(key)));
    }
}

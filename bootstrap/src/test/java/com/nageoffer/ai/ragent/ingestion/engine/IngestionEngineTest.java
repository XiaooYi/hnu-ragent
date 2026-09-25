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

package com.nageoffer.ai.ragent.ingestion.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.node.IngestionNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionEngineTest {

    private final AtomicInteger executedNodes = new AtomicInteger();

    @Test
    @DisplayName("多起点流水线在执行任何节点前失败，并列出全部起始节点")
    void multipleStartNodesFailBeforeExecution() {
        PipelineDefinition pipeline = PipelineDefinition.builder()
                .id("pipeline-1")
                .nodes(List.of(
                        node("a", "parser", null),
                        node("b", "parser", null)))
                .build();

        ClientException exception = assertThrows(ClientException.class,
                () -> engine().execute(pipeline, IngestionContext.builder().build()));

        assertTrue(exception.getMessage().contains("a") && exception.getMessage().contains("b"),
                "错误信息必须列出全部起始节点，实际：" + exception.getMessage());
        assertEquals(0, executedNodes.get(), "配置错误不得执行任何节点");
    }

    @Test
    @DisplayName("无根节点保持原有错误语义")
    void noStartNodeKeepsErrorSemantics() {
        PipelineDefinition pipeline = PipelineDefinition.builder()
                .id("pipeline-1")
                .nodes(List.of(
                        node("a", "parser", "b"),
                        node("b", "parser", "a")))
                .build();

        assertThrows(ClientException.class,
                () -> engine().execute(pipeline, IngestionContext.builder().build()));
    }

    @Test
    @DisplayName("合法单链行为不变：按连线顺序执行")
    void singleChainStillExecutesInOrder() {
        PipelineDefinition pipeline = PipelineDefinition.builder()
                .id("pipeline-1")
                .nodes(List.of(
                        node("a", "parser", "b"),
                        node("b", "parser", null)))
                .build();

        assertDoesNotThrow(() -> engine().execute(pipeline, IngestionContext.builder().build()));
        assertEquals(2, executedNodes.get());
    }

    private IngestionEngine engine() {
        IngestionNode parserNode = new IngestionNode() {
            @Override
            public String getNodeType() {
                return "parser";
            }

            @Override
            public NodeResult execute(IngestionContext context, NodeConfig config) {
                executedNodes.incrementAndGet();
                return NodeResult.ok();
            }
        };
        return new IngestionEngine(
                List.of(parserNode),
                new ConditionEvaluator(new ObjectMapper()),
                new NodeOutputExtractor());
    }

    private NodeConfig node(String nodeId, String nodeType, String nextNodeId) {
        return NodeConfig.builder()
                .nodeId(nodeId)
                .nodeType(nodeType)
                .nextNodeId(nextNodeId)
                .build();
    }
}

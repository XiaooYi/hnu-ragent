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

package com.nageoffer.ai.ragent.ingestion.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.google.gson.Gson;
import com.nageoffer.ai.ragent.audit.annotation.BizChangeLog;
import com.nageoffer.ai.ragent.audit.constant.BizChangeBizType;
import com.nageoffer.ai.ragent.audit.constant.BizChangeOperationType;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.IntentNodeUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.IntentNodeTreeVO;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.enums.IntentLevel;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.core.intent.IntentTreeCacheManager;
import com.nageoffer.ai.ragent.ingestion.service.IntentTreeService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class IntentTreeServiceImpl extends ServiceImpl<IntentNodeMapper, IntentNodeDO> implements IntentTreeService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final IntentTreeCacheManager intentTreeCacheManager;
    private final BizChangeLogContext bizChangeLogContext;

    private static final Gson GSON = new Gson();

    @Override
    public List<IntentNodeTreeVO> getFullTree() {
        List<IntentNodeDO> list = this.list(new LambdaQueryWrapper<IntentNodeDO>()
                .eq(IntentNodeDO::getDeleted, 0)
                .orderByAsc(IntentNodeDO::getSortOrder, IntentNodeDO::getId));

        // 先按 parentCode 分组
        Map<String, List<IntentNodeDO>> parentMap = list.stream()
                .collect(Collectors.groupingBy(node -> {
                    String parent = node.getParentCode();
                    return parent == null ? "ROOT" : parent;
                }));

        // 根节点：parentCode 为空
        List<IntentNodeDO> roots = parentMap.getOrDefault("ROOT", Collections.emptyList());

        // 递归构建树
        List<IntentNodeTreeVO> tree = new ArrayList<>();
        for (IntentNodeDO root : roots) {
            tree.add(buildTree(root, parentMap));
        }
        return tree;
    }

    private IntentNodeTreeVO buildTree(IntentNodeDO current,
                                       Map<String, List<IntentNodeDO>> parentMap) {
        IntentNodeTreeVO result = BeanUtil.toBean(current, IntentNodeTreeVO.class);
        List<IntentNodeDO> children = parentMap.getOrDefault(current.getIntentCode(), Collections.emptyList());

        if (!CollectionUtils.isEmpty(children)) {
            List<IntentNodeTreeVO> childVOs = children.stream()
                    .map(child -> buildTree(child, parentMap))
                    .collect(Collectors.toList());

            result.setChildren(childVOs);
        }

        return result;
    }

    @Override
    @BizChangeLog(
            bizType = BizChangeBizType.INTENT_TREE,
            operationType = BizChangeOperationType.CREATE,
            success = "创建意图节点：{{#requestParam.intentCode}}（{{#requestParam.name}}）",
            fail = "创建意图节点失败：{{#_errorMsg}}"
    )
    public String createNode(IntentNodeCreateRequest requestParam) {
        // 简单重复校验：intentCode 不允许重复
        long count = this.count(new LambdaQueryWrapper<IntentNodeDO>()
                .eq(IntentNodeDO::getIntentCode, requestParam.getIntentCode())
                .eq(IntentNodeDO::getDeleted, 0));
        if (count > 0) {
            throw new ClientException("意图标识已存在: " + requestParam.getIntentCode());
        }

        if (Objects.equals(requestParam.getLevel(), IntentLevel.TOPIC.getCode())
                && Objects.equals(requestParam.getKind(), IntentKind.KB.getCode())
                && StrUtil.isBlank(requestParam.getKbId())) {
            throw new ClientException("TOPIC级别的RAG检索节点必须指定目标知识库");
        }

        List<String> createCollectionNames = resolveCreateCollectionNames(requestParam);
        IntentNodeDO node = IntentNodeDO.builder()
                .intentCode(requestParam.getIntentCode())
                .kbId(
                        StrUtil.isNotBlank(requestParam.getKbId()) ? requestParam.getKbId() : null
                )
                .collectionName(firstCollectionName(createCollectionNames))
                .collectionNames(createCollectionNames)
                .name(requestParam.getName())
                .level(requestParam.getLevel())
                .parentCode(requestParam.getParentCode())
                .description(requestParam.getDescription())
                .mcpToolId(requestParam.getMcpToolId())
                .examples(
                        requestParam.getExamples() == null ? null : GSON.toJson(requestParam.getExamples())
                )
                .topK(normalizeTopK(requestParam.getTopK()))
                .kind(
                        requestParam.getKind() == null ? 0 : requestParam.getKind()
                )
                .sortOrder(
                        requestParam.getSortOrder() == null ? 0 : requestParam.getSortOrder()
                )
                .enabled(
                        requestParam.getEnabled() == null ? 1 : requestParam.getEnabled()
                )
                .createBy(UserContext.getUsername())
                .updateBy(UserContext.getUsername())
                .paramPromptTemplate(requestParam.getParamPromptTemplate())
                .promptSnippet(requestParam.getPromptSnippet())
                .promptTemplate(requestParam.getPromptTemplate())
                .deleted(0)
                .build();

        this.save(node);

        // 清除Redis缓存，下次读取时会重新从数据库加载
        intentTreeCacheManager.clearIntentTreeCache();

        bizChangeLogContext.put(String.valueOf(node.getId()), null, node);
        return String.valueOf(node.getId());
    }

    @Override
    @BizChangeLog(
            bizType = BizChangeBizType.INTENT_TREE,
            operationType = BizChangeOperationType.UPDATE,
            bizId = "#id",
            success = "更新意图节点：{{#id}}",
            fail = "更新意图节点失败：{{#_errorMsg}}"
    )
    public void updateNode(String id, IntentNodeUpdateRequest req) {
        IntentNodeDO node = this.getById(id);
        if (node == null || Objects.equals(node.getDeleted(), 1)) {
            throw new ServiceException("节点不存在或已删除: id=" + id);
        }
        IntentNodeDO before = BeanUtil.copyProperties(node, IntentNodeDO.class);

        if (req.getName() != null) {
            node.setName(req.getName());
        }
        if (req.getLevel() != null) {
            node.setLevel(req.getLevel());
        }
        if (req.getParentCode() != null) {
            node.setParentCode(req.getParentCode());
        }
        if (req.getDescription() != null) {
            node.setDescription(req.getDescription());
        }
        if (req.getExamples() != null) {
            node.setExamples(GSON.toJson(req.getExamples()));
        }
        if (req.getCollectionName() != null) {
            node.setCollectionName(req.getCollectionName());
        }
        if (req.getCollectionNames() != null) {
            List<String> collectionNames = normalizeCollectionNames(req.getCollectionNames());
            node.setCollectionNames(collectionNames);
            // 旧单值字段同步为首个 Collection，供旧版本读侧与旧缓存回退使用
            node.setCollectionName(firstCollectionName(collectionNames));
        }
        if (req.getTopK() != null) {
            node.setTopK(normalizeTopK(req.getTopK()));
        }
        if (req.getKind() != null) {
            node.setKind(req.getKind());
        }
        if (req.getSortOrder() != null) {
            node.setSortOrder(req.getSortOrder());
        }
        if (req.getEnabled() != null) {
            node.setEnabled(req.getEnabled());
        }
        if (req.getPromptSnippet() != null) {
            node.setPromptSnippet(req.getPromptSnippet());
        }
        if (req.getPromptTemplate() != null) {
            node.setPromptTemplate(req.getPromptTemplate());
        }
        if (req.getParamPromptTemplate() != null) {
            node.setParamPromptTemplate(req.getParamPromptTemplate());
        }
        node.setUpdateBy(UserContext.getUsername());
        this.updateById(node);

        // 清除Redis缓存，下次读取时会重新从数据库加载
        intentTreeCacheManager.clearIntentTreeCache();
        bizChangeLogContext.put(id, before, this.getById(id));
    }

    /**
     * 解析创建请求要关联的 Collection：
     * 显式传入的列表优先（允许一个意图覆盖多个库），否则按 kbId 解析出该知识库的 Collection
     */
    private List<String> resolveCreateCollectionNames(IntentNodeCreateRequest requestParam) {
        List<String> explicit = normalizeCollectionNames(requestParam.getCollectionNames());
        if (!explicit.isEmpty()) {
            return explicit;
        }
        if (StrUtil.isBlank(requestParam.getKbId())) {
            return List.of();
        }
        KnowledgeBaseDO knowledgeBase = knowledgeBaseMapper.selectById(requestParam.getKbId());
        return knowledgeBase == null || StrUtil.isBlank(knowledgeBase.getCollectionName())
                ? List.of()
                : List.of(knowledgeBase.getCollectionName().trim());
    }

    /**
     * 清洗 Collection 列表：去空、去重、保序
     */
    private List<String> normalizeCollectionNames(List<String> collectionNames) {
        if (CollectionUtils.isEmpty(collectionNames)) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String name : collectionNames) {
            if (name != null && !name.trim().isEmpty()) {
                normalized.add(name.trim());
            }
        }
        return List.copyOf(normalized);
    }

    private String firstCollectionName(List<String> collectionNames) {
        return collectionNames.isEmpty() ? null : collectionNames.get(0);
    }

    @Override
    @BizChangeLog(
            bizType = BizChangeBizType.INTENT_TREE,
            operationType = BizChangeOperationType.DELETE,
            bizId = "#id",
            success = "删除意图节点：{{#id}}",
            fail = "删除意图节点失败：{{#_errorMsg}}"
    )
    public void deleteNode(String id) {
        IntentNodeDO node = this.getById(id);
        IntentNodeDO before = node == null ? null : BeanUtil.copyProperties(node, IntentNodeDO.class);
        this.removeById(id);

        // 清除Redis缓存，下次读取时会重新从数据库加载
        intentTreeCacheManager.clearIntentTreeCache();
        bizChangeLogContext.put(id, before, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @BizChangeLog(
            bizType = BizChangeBizType.INTENT_TREE,
            operationType = BizChangeOperationType.ENABLE,
            success = "批量启用意图节点：{{#ids}}",
            fail = "批量启用意图节点失败：{{#_errorMsg}}"
    )
    public void batchEnableNodes(List<String> ids) {
        List<IntentNodeDO> targetNodes = listAndValidateTargetNodes(ids);
        String operator = UserContext.getUsername();
        targetNodes.forEach(node -> {
            IntentNodeDO before = BeanUtil.copyProperties(node, IntentNodeDO.class);
            node.setEnabled(1);
            node.setUpdateBy(operator);
            bizChangeLogContext.put(node.getId(), before, node);
        });
        this.updateBatchById(targetNodes);
        intentTreeCacheManager.clearIntentTreeCache();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @BizChangeLog(
            bizType = BizChangeBizType.INTENT_TREE,
            operationType = BizChangeOperationType.DISABLE,
            success = "批量停用意图节点：{{#ids}}",
            fail = "批量停用意图节点失败：{{#_errorMsg}}"
    )
    public void batchDisableNodes(List<String> ids) {
        List<IntentNodeDO> targetNodes = listAndValidateTargetNodes(ids);
        List<IntentNodeDO> allActiveNodes = listActiveNodes();
        Map<String, List<IntentNodeDO>> childrenMap = buildChildrenMap(allActiveNodes);
        Set<String> targetIdSet = targetNodes.stream().map(IntentNodeDO::getId).collect(Collectors.toSet());
        for (IntentNodeDO targetNode : targetNodes) {
            List<IntentNodeDO> descendants = collectDescendants(targetNode.getIntentCode(), childrenMap);
            List<IntentNodeDO> enabledButNotSelected = descendants.stream()
                    .filter(item -> Objects.equals(item.getEnabled(), 1) && !targetIdSet.contains(item.getId()))
                    .collect(Collectors.toList());
            if (CollectionUtils.isNotEmpty(enabledButNotSelected)) {
                throw new ClientException(
                        String.format(
                                "批量停用失败：节点 [%s] 存在已启用的子节点未包含在本次操作中（如：%s），请先选择全量子节点",
                                targetNode.getName(),
                                summarizeNodeNames(enabledButNotSelected)
                        )
                );
            }
        }
        String operator = UserContext.getUsername();
        targetNodes.forEach(node -> {
            IntentNodeDO before = BeanUtil.copyProperties(node, IntentNodeDO.class);
            node.setEnabled(0);
            node.setUpdateBy(operator);
            bizChangeLogContext.put(node.getId(), before, node);
        });
        this.updateBatchById(targetNodes);
        intentTreeCacheManager.clearIntentTreeCache();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @BizChangeLog(
            bizType = BizChangeBizType.INTENT_TREE,
            operationType = BizChangeOperationType.DELETE,
            success = "批量删除意图节点：{{#ids}}",
            fail = "批量删除意图节点失败：{{#_errorMsg}}"
    )
    public void batchDeleteNodes(List<String> ids) {
        List<IntentNodeDO> targetNodes = listAndValidateTargetNodes(ids);
        List<IntentNodeDO> allActiveNodes = listActiveNodes();
        Map<String, List<IntentNodeDO>> childrenMap = buildChildrenMap(allActiveNodes);
        Set<String> targetIdSet = targetNodes.stream().map(IntentNodeDO::getId).collect(Collectors.toSet());
        for (IntentNodeDO targetNode : targetNodes) {
            List<IntentNodeDO> descendants = collectDescendants(targetNode.getIntentCode(), childrenMap);
            List<IntentNodeDO> notSelectedDescendants = descendants.stream()
                    .filter(item -> !targetIdSet.contains(item.getId()))
                    .collect(Collectors.toList());
            if (CollectionUtils.isNotEmpty(notSelectedDescendants)) {
                List<IntentNodeDO> enabledDescendants = notSelectedDescendants.stream()
                        .filter(item -> Objects.equals(item.getEnabled(), 1))
                        .collect(Collectors.toList());
                if (CollectionUtils.isNotEmpty(enabledDescendants)) {
                    throw new ClientException(
                            String.format(
                                    "批量删除失败：节点 [%s] 存在已启用的子节点未包含在本次操作中（如：%s），请先选择全量子节点",
                                    targetNode.getName(),
                                    summarizeNodeNames(enabledDescendants)
                            )
                    );
                }
                throw new ClientException(
                        String.format(
                                "批量删除失败：节点 [%s] 未包含全量子节点（如：%s），请先勾选完整子树后再删除",
                                targetNode.getName(),
                                summarizeNodeNames(notSelectedDescendants)
                        )
                );
            }
        }
        targetNodes.forEach(node -> bizChangeLogContext.put(node.getId(),
                BeanUtil.copyProperties(node, IntentNodeDO.class), null));
        this.removeByIds(targetIdSet);
        intentTreeCacheManager.clearIntentTreeCache();
    }

    /**
     * 规范化节点级 TopK：
     * - null 表示未配置，回退全局默认
     * - 仅允许正整数
     */
    private Integer normalizeTopK(Integer topK) {
        if (topK == null) {
            return null;
        }
        if (topK <= 0) {
            throw new ClientException("节点级 TopK 必须大于 0");
        }
        return topK;
    }

    private List<IntentNodeDO> listAndValidateTargetNodes(List<String> ids) {
        Assert.notEmpty(ids, () -> new ClientException("请至少选择一个节点"));
        List<String> normalizedIds = ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Assert.notEmpty(normalizedIds, () -> new ClientException("节点ID不能为空"));
        List<IntentNodeDO> targetNodes = this.list(new LambdaQueryWrapper<IntentNodeDO>()
                .in(IntentNodeDO::getId, normalizedIds)
                .eq(IntentNodeDO::getDeleted, 0));
        if (targetNodes.size() != normalizedIds.size()) {
            Set<String> existingIds = targetNodes.stream().map(IntentNodeDO::getId).collect(Collectors.toSet());
            List<String> missingIds = normalizedIds.stream()
                    .filter(id -> !existingIds.contains(id))
                    .limit(5)
                    .toList();
            throw new ClientException("节点不存在或已删除: " + missingIds);
        }
        return targetNodes;
    }

    private List<IntentNodeDO> listActiveNodes() {
        return this.list(new LambdaQueryWrapper<IntentNodeDO>()
                .eq(IntentNodeDO::getDeleted, 0));
    }

    private Map<String, List<IntentNodeDO>> buildChildrenMap(List<IntentNodeDO> nodes) {
        return nodes.stream().collect(Collectors.groupingBy(node -> {
            String parentCode = node.getParentCode();
            return parentCode == null ? "ROOT" : parentCode;
        }));
    }

    private List<IntentNodeDO> collectDescendants(String intentCode, Map<String, List<IntentNodeDO>> childrenMap) {
        if (StrUtil.isBlank(intentCode)) {
            return Collections.emptyList();
        }
        List<IntentNodeDO> result = new ArrayList<>();
        Deque<IntentNodeDO> stack = new ArrayDeque<>(
                childrenMap.getOrDefault(intentCode, Collections.emptyList())
        );
        while (!stack.isEmpty()) {
            IntentNodeDO current = stack.pop();
            result.add(current);
            List<IntentNodeDO> children = childrenMap.getOrDefault(current.getIntentCode(), Collections.emptyList());
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
        return result;
    }

    private String summarizeNodeNames(List<IntentNodeDO> nodes) {
        return nodes.stream()
                .limit(3)
                .map(item -> StrUtil.blankToDefault(item.getName(), item.getIntentCode()))
                .collect(Collectors.joining("、"));
    }
}

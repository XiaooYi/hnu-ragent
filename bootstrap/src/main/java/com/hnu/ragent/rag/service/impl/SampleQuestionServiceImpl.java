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

package com.hnu.ragent.rag.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hnu.ragent.audit.annotation.BizChangeLog;
import com.hnu.ragent.audit.constant.BizChangeBizType;
import com.hnu.ragent.audit.constant.BizChangeOperationType;
import com.hnu.ragent.audit.support.BizChangeLogContext;
import com.hnu.ragent.framework.exception.ClientException;
import com.hnu.ragent.rag.controller.request.SampleQuestionCreateRequest;
import com.hnu.ragent.rag.controller.request.SampleQuestionPageRequest;
import com.hnu.ragent.rag.controller.request.SampleQuestionUpdateRequest;
import com.hnu.ragent.rag.controller.vo.SampleQuestionVO;
import com.hnu.ragent.rag.dao.entity.SampleQuestionDO;
import com.hnu.ragent.rag.dao.mapper.SampleQuestionMapper;
import com.hnu.ragent.rag.service.SampleQuestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SampleQuestionServiceImpl implements SampleQuestionService {

    private static final int DEFAULT_LIMIT = 3;

    private final SampleQuestionMapper sampleQuestionMapper;
    private final BizChangeLogContext bizChangeLogContext;

    @Override
    @BizChangeLog(
            bizType = BizChangeBizType.SAMPLE_QUESTION,
            operationType = BizChangeOperationType.CREATE,
            success = "新增示例问题：{{#requestParam.question}}",
            fail = "新增示例问题失败：{{#_errorMsg}}"
    )
    public String create(SampleQuestionCreateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String question = StrUtil.trimToNull(requestParam.getQuestion());
        Assert.notBlank(question, () -> new ClientException("示例问题内容不能为空"));

        SampleQuestionDO record = SampleQuestionDO.builder()
                .title(StrUtil.trimToNull(requestParam.getTitle()))
                .description(StrUtil.trimToNull(requestParam.getDescription()))
                .question(question)
                .build();
        sampleQuestionMapper.insert(record);
        bizChangeLogContext.put(String.valueOf(record.getId()), null, record);
        return String.valueOf(record.getId());
    }

    @Override
    @BizChangeLog(
            bizType = BizChangeBizType.SAMPLE_QUESTION,
            operationType = BizChangeOperationType.UPDATE,
            bizId = "#id",
            success = "更新示例问题：{{#id}}",
            fail = "更新示例问题失败：{{#_errorMsg}}"
    )
    public void update(String id, SampleQuestionUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        SampleQuestionDO record = loadById(id);
        SampleQuestionDO before = BeanUtil.copyProperties(record, SampleQuestionDO.class);

        if (requestParam.getQuestion() != null) {
            String question = StrUtil.trimToNull(requestParam.getQuestion());
            Assert.notBlank(question, () -> new ClientException("示例问题内容不能为空"));
            record.setQuestion(question);
        }
        if (requestParam.getTitle() != null) {
            record.setTitle(StrUtil.trimToNull(requestParam.getTitle()));
        }
        if (requestParam.getDescription() != null) {
            record.setDescription(StrUtil.trimToNull(requestParam.getDescription()));
        }

        sampleQuestionMapper.updateById(record);
        bizChangeLogContext.put(id, before, record);
    }

    @Override
    @BizChangeLog(
            bizType = BizChangeBizType.SAMPLE_QUESTION,
            operationType = BizChangeOperationType.DELETE,
            bizId = "#id",
            success = "删除示例问题：{{#id}}",
            fail = "删除示例问题失败：{{#_errorMsg}}"
    )
    public void delete(String id) {
        SampleQuestionDO record = loadById(id);
        SampleQuestionDO before = BeanUtil.copyProperties(record, SampleQuestionDO.class);
        sampleQuestionMapper.deleteById(record.getId());
        bizChangeLogContext.put(id, before, null);
    }

    @Override
    public SampleQuestionVO queryById(String id) {
        SampleQuestionDO record = loadById(id);
        return toVO(record);
    }

    @Override
    public IPage<SampleQuestionVO> pageQuery(SampleQuestionPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<SampleQuestionDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<SampleQuestionDO> result = sampleQuestionMapper.selectPage(
                page,
                Wrappers.lambdaQuery(SampleQuestionDO.class)
                        .eq(SampleQuestionDO::getDeleted, 0)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(SampleQuestionDO::getTitle, keyword)
                                .or()
                                .like(SampleQuestionDO::getDescription, keyword)
                                .or()
                                .like(SampleQuestionDO::getQuestion, keyword))
                        .orderByDesc(SampleQuestionDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    @Override
    public List<SampleQuestionVO> listRandomQuestions() {
        List<SampleQuestionDO> records = sampleQuestionMapper.selectList(
                Wrappers.lambdaQuery(SampleQuestionDO.class)
                        .eq(SampleQuestionDO::getDeleted, 0)
                        .last("ORDER BY RANDOM() LIMIT " + DEFAULT_LIMIT)
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(this::toVO)
                .toList();
    }

    private SampleQuestionDO loadById(String id) {
        SampleQuestionDO record = sampleQuestionMapper.selectOne(
                Wrappers.lambdaQuery(SampleQuestionDO.class)
                        .eq(SampleQuestionDO::getId, id)
                        .eq(SampleQuestionDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("示例问题不存在"));
        return record;
    }

    private SampleQuestionVO toVO(SampleQuestionDO record) {
        return SampleQuestionVO.builder()
                .id(String.valueOf(record.getId()))
                .title(record.getTitle())
                .description(record.getDescription())
                .question(record.getQuestion())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}

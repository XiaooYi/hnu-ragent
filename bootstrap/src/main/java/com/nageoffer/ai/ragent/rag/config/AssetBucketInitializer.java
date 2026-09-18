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

package com.nageoffer.ai.ragent.rag.config;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 多模态解析产物 bucket(asset-bucket)启动初始化器
 * <p>
 * 系统启动时自动创建 {@code rustfs.asset-bucket} 并下发公共读策略,免去运维手动建桶/配权限
 * 集群环境下用 Redisson 分布式锁保证只建一次:先判断是否存在 → 拿锁 → 双重检查 → 建桶
 * <p>
 * 失败策略:快速失败，S3 / Redis 不可用时让异常向上抛,启动即暴露问题
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetBucketInitializer {

    private static final String LOCK_KEY_PREFIX = "ragent:s3:bucket:init:";
    private static final long LOCK_WAIT_SECONDS = 30;

    private final FileStorageService fileStorageService;
    private final RedissonClient redissonClient;

    @Value("${rustfs.asset-bucket:ragent-assets}")
    private String assetBucket;

    @PostConstruct
    public void initAssetBucket() {
        ensureBucketExists();
        // 幂等下发公共读：无论新建还是已存在，都保证 PDF 抽出的图片可被浏览器匿名直连预览
        fileStorageService.setBucketPublicReadOnly(assetBucket);
        log.info("S3 多模态解析桶就绪（公共读）bucket={}", assetBucket);
    }

    private void ensureBucketExists() {
        if (fileStorageService.bucketExists(assetBucket)) {
            return;
        }

        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + assetBucket);
        boolean locked;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("资产 bucket 初始化获取分布式锁被中断 bucket=" + assetBucket);
        }
        if (!locked) {
            throw new ServiceException("资产 bucket 初始化获取分布式锁超时 bucket=" + assetBucket);
        }

        try {
            if (fileStorageService.bucketExists(assetBucket)) {
                return;
            }
            fileStorageService.createBucket(assetBucket);
            log.info("S3 多模态解析桶创建成功 bucket={}", assetBucket);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}

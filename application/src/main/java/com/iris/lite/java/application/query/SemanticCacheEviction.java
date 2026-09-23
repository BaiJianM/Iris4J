package com.iris.lite.java.application.query;

import com.iris.lite.java.application.cache.CacheService;
import com.iris.lite.java.shared.metrics.IrisMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 语义缓存容量淘汰策略。
 *
 * <p><b>策略</b>：按 (namespace, entity) 给语义条目设上限
 * {@code iris.semantic-cache.max-entries}（&lt;=0 = 不限制），
 * 超限裁掉"剩余 TTL 最短"的条目（≈最早写入，LRU 近似，见仓储实现注释）。
 *
 * <p><b>为什么抽样触发而不是每次 STORE 都检查</b>：检查需要 SCAN + 逐条 PTTL，
 * 上限 1000 条时一次约 190ms。每次写入都跑会让 STORE 的 P99 从微秒劣化到百毫秒级。
 * 折衷：每写入 {@code evict-check-every} 条做一次检查——上限是软上限，
 * 两次检查之间最多超额 checkEvery 条，对"防候选匹配退化"的目标足够
 * （1000 上限多 50 条 = 5% 波动，而无上限时是无限增长）。
 *
 * <p>检查异常只记日志不外抛——淘汰是旁路运维动作，不能影响查询主链路。
 */
@Component
public class SemanticCacheEviction {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheEviction.class);

    /** 语义条目在缓存 key 中的分段标记：{entity}:sem:{fp}:{hard}:{sha}。 */
    static final String SEMANTIC_SEGMENT = ":sem:";

    private final CacheService cacheService;
    private final long maxEntries;
    private final int checkEvery;
    private final AtomicLong storeCounter = new AtomicLong();

    public SemanticCacheEviction(
            CacheService cacheService,
            @Value("${iris.semantic-cache.max-entries:1000}") long maxEntries,
            @Value("${iris.semantic-cache.evict-check-every:50}") int checkEvery) {
        this.cacheService = cacheService;
        this.maxEntries = maxEntries;
        this.checkEvery = Math.max(1, checkEvery);
        if (maxEntries > 0) {
            log.info("语义缓存容量上限已启用 max-entries={} 检查频率=每 {} 次 STORE",
                    maxEntries, this.checkEvery);
        }
    }

    /** 语义条目 STORE 后调用：计数抽样触发容量检查，超限则淘汰。 */
    public void afterStore(String namespace, String entity) {
        if (maxEntries <= 0) {
            return;
        }
        if (storeCounter.incrementAndGet() % checkEvery != 0) {
            return;
        }
        try {
            int evicted = cacheService.evictOldest(namespace, entity, SEMANTIC_SEGMENT, (int) maxEntries);
            if (evicted > 0) {
                IrisMetrics.increment("iris.cache.evictions", evicted,
                        "cache", "semantic", "entity", entity);
            }
        } catch (Exception e) {
            log.warn("语义缓存容量检查失败（不影响查询） ns={} entity={}: {}",
                    namespace, entity, e.getMessage());
        }
    }
}

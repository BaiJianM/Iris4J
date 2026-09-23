package com.iris.lite.java.cache;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 缓存仓储端口。
 *
 * <p><b>精确缓存</b>：相同 key 命中返回缓存值，未命中执行 load 并写入。
 * 值以对象形式传入，序列化细节由实现层（infrastructure）负责，
 * 业务层不感知客户端与 JSON。
 *
 * <p><b>语义缓存</b>：通过 {@link #getAllMatching} 按 key 模式批量读取条目，
 * 相似度计算在调用方（装饰器层）而非实现层——仓储只负责存取，
 * 保持职责单一，也让相似度算法可以独立演进。
 */
public interface CacheRepository {

    /** 读取缓存；未命中或已过期返回 empty。 */
    <T> Optional<T> get(String namespace, String key, Class<T> type);

    /** 写入缓存，ttlSeconds 秒后过期。 */
    void put(String namespace, String key, Object value, long ttlSeconds);

    /** 失效单个缓存 key。 */
    void invalidate(String namespace, String key);

    /**
     * 失效某实体下的全部缓存（key 以 {@code entity:} 为前缀）。
     *
     * <p>由 CDC 投影成功后调用，是保证缓存与源库一致性的关键动作。
     */
    void invalidateEntity(String namespace, String entity);

    /**
     * 按 key 模式批量读取缓存条目（模式匹配语义缓存候选，如 {@code customer:sem:abc:*}）。
     * 单条解析失败只跳过并记日志，不中断整批。
     */
    <T> List<T> getAllMatching(String namespace, String keyPattern, Class<T> type);

    /**
     * 同 {@link #getAllMatching}，但返回「相对 key 段 → 条目」映射（相对段不含
     * {@code iris:{ns}:cache:} 前缀），供重嵌入迁移等需要按 key 定位/删除条目的场景。
     */
    <T> Map<String, T> getAllMatchingWithKeys(String namespace, String keyPattern, Class<T> type);

    /**
     * 容量淘汰：把 {@code {entity}{segment}*} 匹配的条目裁到 keepEntries 以内。
     *
     * <p>淘汰顺序由实现层决定（当前：剩余 TTL 最短优先——TTL 固定时等价于
     * "最早写入的先淘汰"，是 LRU 的低成本近似），返回实际删除条数。
     * Redis 命令（SCAN/PTTL/DEL）只在实现层，业务层只给策略参数。
     */
    int evictOldest(String namespace, String entity, String segment, int keepEntries);
}

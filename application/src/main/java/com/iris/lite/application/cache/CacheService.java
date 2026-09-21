package com.iris.lite.application.cache;

import com.iris.lite.application.query.SemanticCachedEntityQueryService;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 统一缓存服务（精确缓存 + 语义缓存条目存取），REST 与 MCP 共用。
 *
 * <p><b>精确缓存</b>：相同 key 命中直接返回，未命中由调用方 load 后写入。
 * TTL 兜底失效 + CDC 主动失效。
 *
 * <p><b>语义缓存</b>：候选条目经 {@link #getAllMatching} 批量读取，
 * 相似度计算在装饰器层（{@code SemanticCachedEntityQueryService}）而非本层——
 * 本层只负责存取，保持职责单一。
 */
public interface CacheService {

    /** 读取缓存；未命中或已过期返回 empty。 */
    <T> Optional<T> get(String namespace, String key, Class<T> type);

    /** 写入缓存，ttlSeconds 秒后过期。 */
    void put(String namespace, String key, Object value, long ttlSeconds);

    /** 失效单个缓存 key。 */
    void invalidate(String namespace, String key);

    /** 失效某实体下的全部查询缓存（含语义缓存条目）。由 CDC 投影后调用。 */
    void invalidateEntity(String namespace, String entity);

    /**
     * 按 key 模式批量读取缓存条目（语义缓存候选查找）。
     * 单条解析失败只跳过，不中断整批。
     */
    <T> List<T> getAllMatching(String namespace, String keyPattern, Class<T> type);

    /**
     * 同 {@link #getAllMatching}，但返回「相对 key 段 → 条目」映射（不含
     * {@code iris:{ns}:cache:} 前缀），供语义缓存重嵌入迁移按 key 删除旧条目。
     */
    <T> Map<String, T> getAllMatchingWithKeys(String namespace, String keyPattern, Class<T> type);

    /**
     * 容量淘汰：把 {@code {entity}{segment}*} 匹配的缓存条目裁到
     * keepEntries 以内，返回实际删除条数。淘汰顺序（剩余 TTL 最短优先）在仓储层。
     */
    int evictOldest(String namespace, String entity, String segment, int keepEntries);
}

package com.iris.lite.java.application.cache;

import com.iris.lite.java.cache.CacheRepository;
import com.iris.lite.java.shared.error.ErrorCode;
import com.iris.lite.java.shared.error.IrisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 缓存服务默认实现：参数校验 + 委托仓储，不包含任何 Redis 命令。
 *
 * <p><b>这一层存在的意义</b>：把"参数合法性校验"集中在一处，
 * 让 REST 和 MCP 两个入口都不必重复写校验逻辑，
 * 也让仓储实现可以专注在 Redis 交互上、不必关心参数是否合法。
 */
@Service
public class DefaultCacheService implements CacheService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCacheService.class);

    /** 默认 TTL：调用方传入 <=0 时兜底，避免"永不过期"的缓存条目。 */
    private static final long DEFAULT_TTL_SECONDS = 60;

    private final CacheRepository repository;

    public DefaultCacheService(CacheRepository repository) {
        this.repository = repository;
    }

    /** 读取缓存。命中/未命中的业务语义由调用方（查询装饰器）打点，这里不打日志。 */
    @Override
    public <T> Optional<T> get(String namespace, String key, Class<T> type) {
        requireKey(namespace, key);
        return repository.get(namespace, key, type);
    }

    /**
     * 写入缓存。
     *
     * <p><b>TTL <=0 时兜底为默认值</b>：ttl=0 在 Redis SETEX 里是非法参数会报错，
     * 而"永不过期"的缓存条目一旦写入就再也失效不掉（除非 CDC 主动失效命中），
     * 属于运维隐患。兜底成 60 秒更安全。
     */
    @Override
    public void put(String namespace, String key, Object value, long ttlSeconds) {
        requireKey(namespace, key);
        if (value == null) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "缓存值不能为空");
        }
        long ttl = ttlSeconds <= 0 ? DEFAULT_TTL_SECONDS : ttlSeconds;
        repository.put(namespace, key, value, ttl);
    }

    /** 失效单个缓存 key。 */
    @Override
    public void invalidate(String namespace, String key) {
        requireKey(namespace, key);
        repository.invalidate(namespace, key);
    }

    /**
     * 失效某实体下的全部查询缓存（精确缓存 + 语义缓存条目）。
     *
     * <p>由 CDC 投影成功后调用，是保证缓存新鲜度的关键动作。
     */
    @Override
    public void invalidateEntity(String namespace, String entity) {
        if (namespace == null || namespace.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "namespace 不能为空");
        }
        if (entity == null || entity.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "entity 不能为空");
        }
        repository.invalidateEntity(namespace, entity);
    }

    /** 按 key 模式批量读取（只要值）。语义缓存候选查找用。 */
    @Override
    public <T> List<T> getAllMatching(String namespace, String keyPattern, Class<T> type) {
        if (namespace == null || namespace.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "namespace 不能为空");
        }
        if (keyPattern == null || keyPattern.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "缓存 key 模式不能为空");
        }
        return repository.getAllMatching(namespace, keyPattern, type);
    }

    /** 按 key 模式批量读取（含相对 key）。语义缓存重嵌入迁移用。 */
    @Override
    public <T> Map<String, T> getAllMatchingWithKeys(String namespace, String keyPattern, Class<T> type) {
        if (namespace == null || namespace.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "namespace 不能为空");
        }
        if (keyPattern == null || keyPattern.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "缓存 key 模式不能为空");
        }
        return repository.getAllMatchingWithKeys(namespace, keyPattern, type);
    }

    /**
     * 容量淘汰：把 {entity}{segment}* 条目裁到 keepEntries 以内，返回删除数。
     * Redis 执行在仓储层（SCAN/PTTL/DEL），这里只做参数校验与透传。
     */
    @Override
    public int evictOldest(String namespace, String entity, String segment, int keepEntries) {
        if (namespace == null || namespace.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "namespace 不能为空");
        }
        if (entity == null || entity.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "entity 不能为空");
        }
        if (keepEntries <= 0) {
            return 0;
        }
        return repository.evictOldest(namespace, entity, segment, keepEntries);
    }

    /** 校验 namespace 与 key 非空（两者共同决定缓存条目位置，缺一不可）。 */
    private void requireKey(String namespace, String key) {
        if (namespace == null || namespace.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "namespace 不能为空");
        }
        if (key == null || key.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "缓存 key 不能为空");
        }
    }
}

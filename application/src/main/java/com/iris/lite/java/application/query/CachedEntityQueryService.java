package com.iris.lite.java.application.query;

import com.iris.lite.java.application.cache.CacheService;
import com.iris.lite.java.context.model.QueryRequest;
import com.iris.lite.java.shared.metrics.IrisMetrics;
import com.iris.lite.java.shared.model.Page;
import com.iris.lite.java.shared.util.Digests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * 实体查询的精确缓存装饰器（中间层）。
 *
 * <p>相同查询（namespace + entity + fields + filters + tenant + 分页）命中缓存直接返回，
 * 未命中委托 {@link DefaultEntityQueryService} 执行并写入缓存。
 * key = 规范化请求的 SHA-256。TTL 兜底失效 + CDC 主动失效。
 *
 * <p><b>在装饰器链中的位置</b>：
 * {@code AccessControlledEntityQueryService -> SemanticCachedEntityQueryService
 * -> 本类 -> DefaultEntityQueryService}。
 * 本层由语义层按 bean 名注入（{@code @Qualifier("exactCachedQueryService")}），
 * 因此这里不加 {@code @Primary}——{@code @Primary} 在链头访问控制层。
 */
@Service("exactCachedQueryService")
public class CachedEntityQueryService implements EntityQueryService {

    private static final Logger log = LoggerFactory.getLogger(CachedEntityQueryService.class);

    private final EntityQueryService delegate;
    private final CacheService cacheService;
    private final long ttlSeconds;

    public CachedEntityQueryService(
            @Qualifier("delegateQueryService") EntityQueryService delegate,
            CacheService cacheService,
            @Value("${iris.cache.ttl-seconds:60}") long ttlSeconds) {
        this.delegate = delegate;
        this.cacheService = cacheService;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 查询：命中缓存直接返回，未命中执行并回写。
     *
     * <p><b>缓存的是最终分页结果</b>：key 里含 page/pageSize/fields，
     * 所以不同分页/不同字段的请求各自缓存，不会互相串数据。
     */
    @Override
    public Page<Map<String, Object>> query(QueryRequest request) {
        String key = cacheKey(request);

        // 时效旁路：fresh 请求跳过缓存读、强制执行真实查询；
        // 回写保留——本数据快照对后续普通查询依然有效
        Optional<CachedQueryResult> cached = request.fresh()
                ? Optional.empty()
                : cacheService.get(request.namespace(), key, CachedQueryResult.class);
        if (cached.isPresent()) {
            CachedQueryResult r = cached.get();
            IrisMetrics.increment("iris.cache.requests",
                    "cache", "exact", "entity", request.entity(), "result", "hit");
            log.debug("精确缓存 HIT ns={} entity={} key={} items={}",
                    request.namespace(), request.entity(), key, r.items().size());
            return new Page<>(r.items(), r.total(), r.page(), r.pageSize());
        }

        log.debug("精确缓存 MISS ns={} entity={} key={} ttl={}s{}",
                request.namespace(), request.entity(), key, ttlSeconds,
                request.fresh() ? "（时效词旁路）" : "");
        IrisMetrics.increment("iris.cache.requests",
                "cache", "exact", "entity", request.entity(), "result",
                request.fresh() ? "bypass" : "miss");
        Page<Map<String, Object>> result = delegate.query(request);
        CachedQueryResult snapshot =
                new CachedQueryResult(result.items(), result.total(), result.page(), result.pageSize());
        cacheService.put(request.namespace(), key, snapshot, ttlSeconds);
        return result;
    }

    /**
     * 聚合不走精确缓存：聚合本身是服务端一次 FT.AGGREGATE 命令，
     * 且统计值对 CDC 新鲜度最敏感（缓存收益为负），直接透传下一层。
     */
    @Override
    public com.iris.lite.java.context.model.AggregateResult aggregate(
            com.iris.lite.java.context.model.AggregateRequest request) {
        return delegate.aggregate(request);
    }

    /**
     * 缓存 key = 实体前缀 + 规范化请求哈希前 16 字节。
     *
     * <p>规范化（哪些请求成分参与 key、如何消除顺序差异）集中在
     * {@link RequestCanonicalizer}——exact 与 semantic 两级缓存共用同一份清单，
     * 未来增加请求字段不会出现「两处标准漂移」导致的串号。本层传 null schema
     * = exact 模式：全部过滤值进 key，不同条件的结果绝不互串。
     *
     * <p>key 带 entity 前缀，便于按实体整体失效（CDC 主动失效），
     * 最终 Redis key = iris:{ns}:cache:{entity}:{hash}。
     */
    private String cacheKey(QueryRequest request) {
        String canonical = RequestCanonicalizer.canonical(request, null);
        return request.entity() + ":" + Digests.sha256Hex(canonical).substring(0, 16);
    }
}

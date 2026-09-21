package com.iris.lite.application.query;

import com.iris.lite.application.cache.CacheService;
import com.iris.lite.application.cache.SemanticReindexService;
import com.iris.lite.cache.embedder.Embedder;
import com.iris.lite.context.model.QueryRequest;
import com.iris.lite.context.schema.EntitySchema;
import com.iris.lite.context.schema.FieldType;
import com.iris.lite.context.schema.SchemaProvider;
import com.iris.lite.shared.metrics.IrisMetrics;
import com.iris.lite.shared.model.Page;
import com.iris.lite.shared.util.Digests;
import com.iris.lite.shared.util.Vectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 语义缓存装饰器：查询链路 access-control -> semantic -> exact -> default
 * （本层外还有访问控制层，见 {@link AccessControlledEntityQueryService}）。
 *
 * <p><b>作用域刻意收窄</b>：
 * <ul>
 *   <li><b>硬约束精确匹配</b>：entity、tenant、fields、分页、非字符串过滤值
 *       （数字/布尔/时间——{@code level=1} 与 {@code level=2} 字面太像，绝不模糊命中）、
 *       字符串过滤 key 的集合，全部进 hardKey 做精确等值；</li>
 *   <li><b>值级语义匹配</b>：仅 Schema 声明为 STRING 的过滤<b>值</b>做向量余弦相似
 *       （如 {@code city=上海} 与 {@code city=上海市}），达到阈值即命中返回同结果；</li>
 *   <li><b>无字符串过滤值的查询直接跳过</b>语义层（退化为精确缓存）。</li>
 * </ul>
 *
 * <p><b>为什么这样收窄</b>：通用"整句语义缓存"在结构化查询上极其危险——
 * 两个过滤条件数值不同的查询，自然语言表述几乎一样，会被判为相似而返回错误数据。
 * 把模糊匹配严格限制在 STRING 类型的<b>值</b>上（"上海"≈"上海市"），
 * 才既有实用价值又不至于出错。
 *
 * <p><b>Embedder 注入方式</b>：通过 {@link ObjectProvider} 而非直接注入
 * （与 MemoryExtractor 同一模式）——没有 Embedder bean 或开关关闭时整体旁路，
 * 不会因缺 bean 导致启动失败。
 *
 * <p><b>多租户安全</b>：tenant 在 hardKey 中，不同租户的语义条目天然隔离。
 */
@Service("semanticCachedQueryService")
public class SemanticCachedEntityQueryService implements EntityQueryService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCachedEntityQueryService.class);

    private final EntityQueryService delegate;
    private final CacheService cacheService;
    private final SchemaProvider schemaProvider;
    private final ObjectProvider<Embedder> embedderProvider;
    private final SemanticCacheEviction eviction;
    private final boolean enabled;
    private final double threshold;
    private final long ttlSeconds;

    public SemanticCachedEntityQueryService(
            @Qualifier("exactCachedQueryService") EntityQueryService delegate,
            CacheService cacheService,
            SchemaProvider schemaProvider,
            ObjectProvider<Embedder> embedderProvider,
            SemanticCacheEviction eviction,
            @Value("${iris.semantic-cache.enabled:false}") boolean enabled,
            // 默认阈值 0.85：真语义模型基准（同义 0.92 / 同类不同值 0.53 / 无关 0.31）；
            // bm25 词法近似下无意义，正式部署以 yml 标定值覆盖
            @Value("${iris.semantic-cache.threshold:0.85}") double threshold,
            @Value("${iris.cache.ttl-seconds:60}") long ttlSeconds) {
        this.delegate = delegate;
        this.cacheService = cacheService;
        this.schemaProvider = schemaProvider;
        this.embedderProvider = embedderProvider;
        this.eviction = eviction;
        this.enabled = enabled;
        this.threshold = threshold;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 查询：先尝试语义命中，未命中走精确缓存层并回写语义条目。
     *
     * <p><b>三层降级路径</b>：
     * 开关关闭/无 Embedder -> 直接精确缓存层；
     * 无 STRING 过滤值 -> 直接精确缓存层；
     * 相似度未达阈值 -> 精确缓存层执行后回写语义条目。
     */
    @Override
    public Page<Map<String, Object>> query(QueryRequest request) {
        // 时效旁路：问题含「最新/现在」类指示词时跳过语义读，
        // 直达下一层（精确层同样跳过读）——语义条目本质是历史快照，与
        // 「以说话时刻为准」的问题语义天然冲突。不做语义回写：fresh 结果
        // 的可见时间窗极短，写入只占容量（下游精确层仍会回写，不丢缓存）。
        if (request.fresh()) {
            log.debug("时效词旁路语义缓存 ns={} entity={}", request.namespace(), request.entity());
            IrisMetrics.increment("iris.cache.requests",
                    "cache", "semantic", "entity", request.entity(), "result", "bypass");
            return delegate.query(request);
        }
        Embedder embedder = embedderProvider.getIfAvailable();
        if (!enabled || embedder == null) {
            // 语义缓存旁路（开关关闭或无 Embedder bean）
            return delegate.query(request);
        }

        EntitySchema schema = schemaProvider.get(request.namespace(), request.entity());
        FilterSplit split = splitFilters(schema, request);
        // 无 STRING 过滤值：没有可模糊匹配的成分，直接走精确缓存
        if (split.semanticText().isEmpty()) {
            log.debug("无字符串过滤值，跳过语义层 ns={} entity={}",
                    request.namespace(), request.entity());
            return delegate.query(request);
        }

        String hardKey = Digests.sha256Hex(split.hardText()).substring(0, 16);
        // 指纹段做索引版本隔离（双索引切换）：换 Embedder 模型/维度后新条目进新命名空间，
        // 旧命名空间条目经 SemanticReindexService 重嵌入迁移，互不污染
        String fpHash = embedder.fingerprintHash();
        String pattern = request.entity() + ":sem:" + fpHash + ":" + hardKey + ":*";
        float[] vector = embedder.embed(split.semanticText());

        // 候选查找：结构完全一致的条目里做值级余弦相似
        List<SemanticCacheEntry> candidates = cacheService.getAllMatching(
                request.namespace(), pattern, SemanticCacheEntry.class);
        SemanticCacheEntry best = null;
        double bestSim = 0;
        for (SemanticCacheEntry entry : candidates) {
            // 跳过不兼容条目：向量为空、维度不一致（换过模型）、结果缺失
            if (entry.vector() == null
                    || entry.vector().length != embedder.dimension()
                    || entry.result() == null) {
                continue;
            }
            double sim = Vectors.cosine(vector, entry.vector());
            if (sim > bestSim) {
                bestSim = sim;
                best = entry;
            }
        }
        if (best != null && bestSim >= threshold) {
            IrisMetrics.increment("iris.cache.requests",
                    "cache", "semantic", "entity", request.entity(), "result", "hit");
            log.info("语义缓存 HIT sim={} query={} matched={}", bestSim, split.semanticText(), best.filtersText());
            CachedQueryResult r = best.result();
            return new Page<>(r.items(), r.total(), r.page(), r.pageSize());
        }

        // 未命中：走精确缓存层执行，然后回写语义条目供后续相似查询命中。
        //
        // <b>空结果不回写</b>：查询 city=北京市 精确返回 0 条
        // （库里没有该值），若把这个空结果写成语义条目，同义的 city=北京 查询
        // （sim≈0.92）会模糊命中它、错误地返回 0 条——空结果往往意味着
        // "该字面值不在数据中"，它与"语义相近的真实值"是两种情况，
        // 让空条目参与模糊匹配会污染同义值查询。空结果直接返回，不 STORE。
        Page<Map<String, Object>> result = delegate.query(request);
        IrisMetrics.increment("iris.cache.requests",
                "cache", "semantic", "entity", request.entity(), "result", "miss");
        log.debug("语义缓存 MISS ns={} entity={} query={} 候选={} 最高相似度={} 阈值={}",
                request.namespace(), request.entity(), split.semanticText(),
                candidates.size(), bestSim, threshold);
        if (result.total() == 0) {
            log.debug("语义缓存 MISS 且结果为空，不回写条目 ns={} entity={} query={}",
                    request.namespace(), request.entity(), split.semanticText());
            return result;
        }
        String entryKey = request.entity() + ":sem:" + fpHash + ":" + hardKey + ":"
                + Digests.sha256Hex(split.semanticText()).substring(0, 12);
        cacheService.put(request.namespace(), entryKey,
                new SemanticCacheEntry(split.semanticText(), vector,
                        new CachedQueryResult(result.items(), result.total(),
                                result.page(), result.pageSize()),
                        embedder.fingerprint(),
                        OffsetDateTime.now().toString()),
                ttlSeconds);
        log.info("语义缓存 STORE query={} candidates={}", split.semanticText(), candidates.size());
        // 容量淘汰抽样检查（内部按写入次数抽样，超上限才真正 SCAN+淘汰）
        eviction.afterStore(request.namespace(), request.entity());
        return result;
    }

    /**
     * 聚合不走语义缓存：FT.AGGREGATE 本身微秒~毫秒级且统计值
     * 对 CDC 新鲜度最敏感（守卫拦截率会很高，缓存纯负收益），直接透传下一层。
     */
    @Override
    public com.iris.lite.context.model.AggregateResult aggregate(
            com.iris.lite.context.model.AggregateRequest request) {
        return delegate.aggregate(request);
    }

    /**
     * 把过滤条件拆成「硬约束部分」与「可模糊部分」。
     *
     * <p><b>硬约束文本</b>：由 {@link RequestCanonicalizer#canonical} 统一产出——
     * 哪些成分参与 key（ns/entity/tenant/fields/分页/排序/非 STRING 过滤值/范围/文本）
     * 与精确缓存层共用同一份清单，杜绝两处标准漂移。STRING 标量值被剔除出 key
     * （只保留键存在性，键集合精确一致候选才同构），它们进 semanticText 参与向量相似。
     *
     * <p><b>语义文本</b>：仅 Schema 声明为 STRING 的过滤值，按字段名排序后拼接——
     * 排序保证同条件不同传参顺序的请求 embed 出同一向量。
     *
     * <p>STRING 分类（含集合值/未知字段的保守处理）的唯一判定出处见
     * {@link RequestCanonicalizer#isSemanticValue}。
     */
    private FilterSplit splitFilters(EntitySchema schema, QueryRequest request) {
        Map<String, String> stringPairs = new TreeMap<>();
        request.filters().forEach((k, v) -> {
            if (RequestCanonicalizer.isSemanticValue(schema, k, v)) {
                stringPairs.put(k, v == null ? "null" : String.valueOf(v));
            }
        });
        String semanticText = String.join(";", stringPairs.values());
        return new FilterSplit(RequestCanonicalizer.canonical(request, schema), semanticText);
    }

    /** 过滤条件拆分结果：硬约束文本（精确等值，格式见 {@link RequestCanonicalizer}）+ 可模糊匹配的语义文本。 */
    private record FilterSplit(String hardText, String semanticText) {
    }
}

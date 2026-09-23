package com.iris4j.infrastructure.redis;

import com.iris4j.application.query.DefaultEntityQueryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris4j.context.model.AggregateRequest;
import com.iris4j.context.model.AggregateResult;
import com.iris4j.context.model.EntityProjection;
import com.iris4j.context.model.ProjectionPage;
import com.iris4j.context.model.QueryRequest;
import com.iris4j.context.model.RangeFilter;
import com.iris4j.context.repository.EntityProjectionRepository;
import com.iris4j.context.schema.EntitySchema;
import com.iris4j.context.schema.FieldSchema;
import com.iris4j.context.schema.SchemaProvider;
import com.iris4j.shared.error.ErrorCode;
import com.iris4j.shared.error.IrisException;
import com.iris4j.shared.key.KeyStrategy;
import com.iris4j.shared.model.EntityKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 RedisJSON 的实体投影仓储实现。
 *
 * <p><b>三条查询路径（性能差异巨大）</b>：
 * <ol>
 *   <li><b>主键查询</b>：filters 覆盖全部主键字段时，直接拼出 key 做 JSON.GET，
 *       一次网络往返命中。</li>
 *   <li><b>索引查询</b>：索引可用且全部过滤字段可下推时走 FT.SEARCH，
 *       过滤 + 排序 + 分页全部在服务端完成，代价与命中量相关、与实体总量无关。</li>
 *   <li><b>SCAN 降级</b>：索引不可用（未建/建失败/字段未声明 indexed）时退化为
 *       SCAN {@code iris:{ns}:entity:{entity}:*} + 逐条 JSON.GET + 内存匹配。
 *       语义与索引路径一致，只是慢——降级保证"查得到"，性能红线靠索引保证。</li>
 * </ol>
 */
@Component
public class LettuceEntityProjectionRepository implements EntityProjectionRepository {

    /** 聚合指标操作名（与 AggregateReduce.op / MergeState 归并分支共用，字面量收口防漂移）。 */
    private static final String OP_COUNT = "count";
    private static final String OP_AVG = "avg";
    private static final String OP_MIN = "min";
    private static final String OP_MAX = "max";

        private static final Logger log = LoggerFactory.getLogger(LettuceEntityProjectionRepository.class);

    private final RedisAdapter redis;
    private final KeyStrategy keys;
    private final SchemaProvider schemaProvider;
    private final EntityIndexManager indexManager;
    private final ObjectMapper objectMapper;

    /** 维度归并：归并前分组数上限（内存预算参数，非魔法数——每组中间态 ~200B，2 万组 ≈ 4MB）。 */
    private final int dimensionMaxInputGroups;
    /** 维度归并：映射批量拉取的 pipeline 批大小。 */
    private final int dimensionFetchBatch;
    /** 维度归并：映射缺失策略 keep|discard|error。 */
    private final String dimensionUnknownGroup;

    public LettuceEntityProjectionRepository(
            RedisAdapter redis,
            KeyStrategy keys,
            SchemaProvider schemaProvider,
            EntityIndexManager indexManager,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${iris.aggregate.dimension.max-input-groups:20000}") int dimensionMaxInputGroups,
            @org.springframework.beans.factory.annotation.Value(
                    "${iris.aggregate.dimension.fetch-batch:500}") int dimensionFetchBatch,
            @org.springframework.beans.factory.annotation.Value(
                    "${iris.aggregate.dimension.unknown-group:keep}") String dimensionUnknownGroup) {
        this.redis = redis;
        this.keys = keys;
        this.schemaProvider = schemaProvider;
        this.indexManager = indexManager;
        this.objectMapper = objectMapper;
        this.dimensionMaxInputGroups = Math.max(100, dimensionMaxInputGroups);
        this.dimensionFetchBatch = Math.max(10, dimensionFetchBatch);
        this.dimensionUnknownGroup = dimensionUnknownGroup == null || dimensionUnknownGroup.isBlank()
                ? "keep" : dimensionUnknownGroup.toLowerCase();
    }

    /**
     * 写入或更新实体投影（整文档覆盖）。
     *
     * @throws IllegalStateException 序列化失败时抛出，会让 CDC 消费走失败分支
     *                               （不 XACK，留 PEL 等待重试/转 DLQ）
     */
    @Override
    public void upsert(EntityProjection projection) {
        try {
            String json = objectMapper.writeValueAsString(projection.data());
            String key = keys.entityKey(projection.key());
            redis.jsonSet(key, json);
            log.debug("投影已写入 key={} 字段数={}", key, projection.data().size());
        } catch (Exception e) {
            throw new IllegalStateException("投影写入失败: " + projection.key(), e);
        }
    }

    /** 删除实体投影（DEL key）。 */
    @Override
    public void delete(EntityKey key) {
        String redisKey = keys.entityKey(key);
        redis.del(redisKey);
        log.debug("投影已删除 key={}", redisKey);
    }

    /** 按主键裸读单个投影文档（JSON.GET 一次命中）；不存在返回 null。跨表携带字段回填/回刷用。 */
    @Override
    public Map<String, Object> get(EntityKey key) {
        String json = redis.jsonGet(keys.entityKey(key));
        return json == null ? null : readJson(json);
    }

    /**
     * 按查询请求检索实体投影（分页下推）。
     *
     * <p>路径选择顺序：主键覆盖 → 索引可用且可下推 → SCAN 降级。
     * 三条路径返回的 {@link ProjectionPage} 语义一致：items 已分页、total 为匹配总数。
     */
    @Override
    public ProjectionPage find(QueryRequest request) {
        EntitySchema schema = schemaProvider.get(request.namespace(), request.entity());
        List<String> pks = schema.primaryKeys();

        // ---- 路径 1：主键精确查询 ----
        // filters 覆盖全部主键字段（首期单主键即"含主键字段"），可直接定位 key。
        // 例外：主键过滤值是集合时是"IN 任一命中"的过滤查询而非精确定位，
        // 不能用 String.valueOf 拼出 "[1001, 1002]" 这样的畸形 key——
        // 落到索引/SCAN 路径处理（两者的集合值翻译都正确）。
        Object pkFilterValue = request.filters().keySet().containsAll(pks)
                ? request.filters().get(pks.get(0)) : null;
        if (pkFilterValue != null && !(pkFilterValue instanceof Collection<?>)) {
            String pkValue = String.valueOf(pkFilterValue);
            String key = keys.entityKey(request.namespace(), request.entity(), pkValue);
            String json = redis.jsonGet(key);
            if (json == null) {
                log.debug("主键查询未命中 key={}", key);
                return ProjectionPage.empty();
            }
            Map<String, Object> data = readJson(json);
            // 主键命中后仍须校验其余过滤条件（含注入的租户条件）——
            // 直接返回会漏掉非主键条件，也是行级租户隔离的越权点：
            // 攻击者可以用 t1 的 tenant 参数去精确查 t2 的主键，不校验就直接返回了
            if (!matches(data, request.filters())) {
                log.debug("主键命中但过滤条件不匹配（疑似跨租户访问）key={}", key);
                return ProjectionPage.empty();
            }
            log.debug("主键查询命中 key={} 字段数={}", key, data.size());
            // 单行结果：page=1 返回该行，page>1 返回空但 total 仍为 1（与分页语义一致）
            long offset = (long) (request.page() - 1) * request.pageSize();
            List<EntityProjection> items = offset == 0
                    ? List.of(new EntityProjection(
                            new EntityKey(request.namespace(), request.entity(), pkValue), data))
                    : List.of();
            return new ProjectionPage(items, 1);
        }

        // ---- 路径 2：索引查询（过滤 + 排序 + 分页全下推）----
        // 排序字段是 TEXT 形态时索引内不可 SORTBY（TEXT 不标 SORTABLE，长文本排序代价大），
        // 直接走 SCAN 内存排序——与索引路径结果语义一致
        // isQueryReady 而非 isAvailable：索引"回填中"时也必须降级——异步回填完成前
        // FT.SEARCH 返回空，与"表里真没数据"无法区分，会让上层（尤其 Agent）误判数据不存在
        if (indexManager.isQueryReady(request.namespace(), request.entity())
                && !textSorted(request, schema)) {
            String query = SearchQueryTranslator.translate(
                    request.filters(), request.rangeFilters(), request.textFilters(), schema);
            // query == null = 存在下推不了的条件（字段未建索引/数值非法），走降级
            if (query != null) {
                try {
                    return findByIndex(request, schema, query);
                } catch (Exception e) {
                    // 索引查询意外失败（如索引正在重建）不抛给上层，降级 SCAN 保正确性。
                    // 完整堆栈进日志：降级会掩盖性能问题，没有堆栈就无法定位根因
                    log.warn("索引查询失败，降级 SCAN ns={} entity={} 原因={}",
                            request.namespace(), request.entity(), e.getMessage(), e);
                }
            }
        }

        // ---- 路径 3：SCAN 降级（内存过滤 + 内存排序 + 内存分页，语义与索引路径一致）----
        return findByScan(request, schema, pks);
    }

    /**
     * 排序字段是否 TEXT 形态（TEXT 不可 SORTBY，需降级 SCAN 内存排序）。
     * 未指定排序返回 false（索引路径默认主键排序）。
     */
    private boolean textSorted(QueryRequest request, EntitySchema schema) {
        if (request.sortField() == null) {
            return false;
        }
        return schema.fields().stream()
                .filter(f -> f.name().equals(request.sortField()))
                .findFirst()
                .map(f -> SearchQueryTranslator.indexTypeOf(f)
                        == SearchQueryTranslator.FtType.TEXT)
                .orElse(false);
    }

    /**
     * 索引查询路径：FT.SEARCH 一次命令完成过滤/排序/分页。
     *
     * <p>排序：请求带 sortField 时下推 {@code SORTBY sortField ASC/DESC}
     * （字段须 SORTABLE——建索引已全字段标 SORTABLE）；缺省 {@code SORTBY <主键> ASC}
     * 保证深翻页结果稳定。total 经 {@link #stabilizedTotal} 稳定化：引擎后台重扫
     * 期间单次读数可能是部分计数，直接透传会把假总数吐给上层（Agent 会拿它当真回答用户）。
     */
    private ProjectionPage findByIndex(QueryRequest request, EntitySchema schema, String query) {
        String indexName = keys.indexKey(request.namespace(), request.entity());
        long offset = (long) (request.page() - 1) * request.pageSize();
        String sortField = request.sortField() == null
                ? schema.primaryKeys().get(0) : request.sortField();
        RedisAdapter.FtSearchResult result = redis.ftSearch(
                indexName, query, offset, request.pageSize(), sortField, request.sortDesc());
        long total = stabilizedTotal(indexName, query, result.total());
        List<EntityProjection> items = new ArrayList<>(result.hits().size());
        for (RedisAdapter.FtSearchResult.Hit hit : result.hits()) {
            Map<String, Object> data = readJson(hit.json());
            String pkValue = String.valueOf(data.get(schema.primaryKeys().get(0)));
            items.add(new EntityProjection(
                    new EntityKey(request.namespace(), request.entity(), pkValue), data));
        }
        log.debug("索引查询完成 ns={} entity={} total={} 本页={} offset={} query='{}'",
                request.namespace(), request.entity(), total, items.size(), offset, query);
        return new ProjectionPage(items, total);
    }

    /** num_docs 短缓存：稳态下 '*' 查询直接取它，省一次 FT.INFO 往返。 */
    private record NumDocsSnapshot(long value, long expiresAtNanos) {
    }

    /** num_docs 缓存 TTL：太长会放大 CDC 删除后的总数滞后，60s 够用。 */
    private static final long NUM_DOCS_CACHE_TTL_NANOS = 60_000_000_000L;

    private final ConcurrentHashMap<String, NumDocsSnapshot> numDocsCache = new ConcurrentHashMap<>();

    /**
     * total 稳定化护栏：
     * 引擎对大索引做自发段整理/重扫时，FT.SEARCH 的 total 会静默返回
     * 「已重扫部分」的假计数（可能像 2518→28518→10818→50000 这样跳变后自愈），期间
     * num_docs 记账恒为真值。
     *
     * <ul>
     *   <li><b>无过滤查询（query='*'）</b>：匹配全集，读数与 num_docs 不一致
     *       即判定处于重扫窗口，直接采信 num_docs（缓存量级 O(索引数)，内存可忽略）；</li>
     *   <li><b>有过滤查询</b>：NOCONTENT 复读比对，连续两次一致才采信；
     *       3 次退避重试仍漂移则 WARN 并采用最后一次复读值——宁可慢一拍，
     *       也不静默把假总数吐给上层。</li>
     * </ul>
     */
    private long stabilizedTotal(String indexName, String query, long searchTotal) {
        if ("*".equals(query.trim())) {
            long numDocs = numDocsCached(indexName);
            if (numDocs >= 0 && numDocs != searchTotal) {
                log.debug("total 稳定化('*')：search={} != num_docs={}，采信 num_docs（疑似引擎重扫窗口）",
                        searchTotal, numDocs);
                return numDocs;
            }
            return searchTotal;
        }
        long last = searchTotal;
        for (int attempt = 0; attempt < 3; attempt++) {
            long recount = redis.ftSearchCount(indexName, query);
            if (recount == last) {
                return recount;
            }
            last = recount;
            try {
                Thread.sleep(100L * (attempt + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("FT.SEARCH total 连续漂移 index={} query='{}' 采用最后一次复读值={}（引擎疑似重扫窗口）",
                indexName, query, last);
        return last;
    }

    /** num_docs 短缓存读取；FT.INFO 失败（索引不存在等）返回 -1，调用方回落 searchTotal。 */
    private long numDocsCached(String indexName) {
        long now = System.nanoTime();
        NumDocsSnapshot snapshot = numDocsCache.get(indexName);
        if (snapshot != null && now - snapshot.expiresAtNanos() < 0) {
            return snapshot.value();
        }
        long value;
        try {
            value = redis.ftInfo(indexName).numDocs();
        } catch (Exception e) {
            return -1;
        }
        numDocsCache.put(indexName, new NumDocsSnapshot(value, now + NUM_DOCS_CACHE_TTL_NANOS));
        return value;
    }

    /**
     * SCAN 降级路径：全量扫描 + 内存过滤 + 内存排序 + 内存分页（语义兜底路径）。
     *
     * <p>total 必须先收集<b>全部</b>匹配再算——边扫边截会漏掉排序靠后但应计入 total 的行。
     * 这条路径的存在理由是正确性而非性能，索引就绪后不应被走到。
     *
     * <p><b>排序语义与索引路径一致</b>：请求带 sortField 时按该字段排序
     * （数值感知比较：两侧都是数字按数值比，否则按字符串比——JSON 反序列化后的
     * 类型不保证，与 matches() 的 String.valueOf 策略同理）；DESC 反转。
     * 未指定排序保持扫描序（与旧行为一致）。
     */
    private ProjectionPage findByScan(QueryRequest request, EntitySchema schema, List<String> pks) {
        List<EntityProjection> matched = new ArrayList<>();
        String pattern = keys.entityKey(request.namespace(), request.entity(), "*");
        List<String> scanned = redis.scanKeys(pattern);
        // 分批 pipeline 拉文档体：降级窗口（索引回填/故障）内可能扫到成百上千 key，
        // 逐条同步 JSON.GET 是数量级浪费（批内命中序与扫描序一致，语义不变）
        int batchSize = 500;
        for (int from = 0; from < scanned.size(); from += batchSize) {
            List<String> chunk = scanned.subList(from, Math.min(from + batchSize, scanned.size()));
            List<String> jsons;
            try {
                jsons = redis.jsonGetPathBatch(chunk, "$");
            } catch (Exception e) {
                // 批量失败回落逐条（单 key 异常不该拖垮整批）
                log.debug("SCAN 降级批量读取失败，回落逐条: {}", e.getMessage(), e);
                jsons = new ArrayList<>(chunk.size());
                for (String key : chunk) {
                    try {
                        jsons.add(redis.jsonGet(key));
                    } catch (Exception ignored) {
                        jsons.add(null);
                    }
                }
            }
            for (String json : jsons) {
                // 两次调用之间 key 可能已过期/被删，跳过即可
                if (json == null) {
                    continue;
                }
                // JSON.GET 带 path（$）恒返回数组包裹 [doc]，剥掉再解析
                Map<String, Object> data = readJson(RedisAdapter.unwrapJsonPathResult(json));
                if (matches(data, request.filters())) {
                    String pkValue = String.valueOf(data.get(pks.get(0)));
                    matched.add(new EntityProjection(
                            new EntityKey(request.namespace(), request.entity(), pkValue), data));
                }
            }
        }
        // 扫描量与命中量是判断"降级是否在被频繁触发"的直接依据
        log.debug("SCAN 降级完成 ns={} entity={} 扫描={} 命中={} filters={}",
                request.namespace(), request.entity(), scanned.size(), matched.size(),
                request.filters());
        if (request.sortField() != null) {
            Comparator<EntityProjection> byField = (p1, p2) ->
                    compareSortValues(p1.data().get(request.sortField()),
                            p2.data().get(request.sortField()));
            if (request.sortDesc()) {
                byField = byField.reversed();
            }
            matched.sort(byField);
        }
        // 内存分页：subList 截取语义（offset 越界返回空页）
        long offset = (long) (request.page() - 1) * request.pageSize();
        List<EntityProjection> items = offset >= matched.size() ? List.of()
                : matched.subList((int) offset,
                        (int) Math.min(offset + request.pageSize(), matched.size()));
        return new ProjectionPage(items, matched.size());
    }

    /**
     * 服务端聚合（瓶颈分析 A1）：FT.AGGREGATE 一次命令完成 GROUPBY + 归约 + TopN。
     *
     * <p><b>无 SCAN 降级</b>（与 find() 的三条路径刻意不同）：聚合的对象是
     * 「全量数据的统计特征」，SCAN 聚合 = 请求路径上扫全 keyspace，是本项目红线。
     * 索引未就绪/条件不可下推时抛 {@link IrisException}，上层转可读错误让
     * 调用方（Agent）改用别的标准，而不是等 5~7 秒的全库扫描。
     *
     * <p><b>字段校验（fail-closed）</b>：groupBy/sortBy 字段必须已索引（任意形态），
     * sum/avg/min/max 的 field 必须是 numeric 索引——COUNT 之外对非索引字段聚合
     * 要么引擎报错要么静默漏数，不如应用层直接拒绝并说清原因。
     */
    @Override
    public AggregateResult aggregate(AggregateRequest request) {
        // 维度归并分流：group_by 含 "fk->entity.dim" 路径时走归并路径（应用层合并重算），
        // 否则原 FT.AGGREGATE 单实体聚合不变
        if (!request.dimensionPaths().isEmpty()) {
            return aggregateWithDimension(request, request.dimensionPaths().get(0));
        }
        EntitySchema schema = schemaProvider.get(request.namespace(), request.entity());
        String indexName = keys.indexKey(request.namespace(), request.entity());
        // 索引必须就绪（isQueryReady 含回填中判断）：回填中聚合会得到静默偏小的统计值
        if (!indexManager.isQueryReady(request.namespace(), request.entity())) {
            throw new IrisException(ErrorCode.INVALID_QUERY,
                    "实体 " + request.entity() + " 的索引未就绪，聚合暂不可用（回填中或索引缺失）");
        }
        Map<String, FieldSchema> byName = new LinkedHashMap<>();
        for (FieldSchema f : schema.fields()) {
            byName.put(f.name(), f);
        }
        // ---- groupBy / sortBy / metric 字段校验 ----
        for (String g : request.groupBy()) {
            FieldSchema f = byName.get(g);
            if (f == null) {
                throw new IrisException(ErrorCode.INVALID_QUERY,
                        "分组字段不存在: " + g + "（实体 " + request.entity() + "）");
            }
            if (f.effectiveIndex() == null) {
                throw new IrisException(ErrorCode.INVALID_QUERY,
                        "分组字段 " + g + " 未建索引，无法聚合；可先查该字段有哪些取值再逐个过滤");
            }
        }
        List<RedisAdapter.AggregateReduce> reduces = new ArrayList<>();
        for (AggregateRequest.Metric m : request.metrics()) {
            if (!OP_COUNT.equals(m.op())) {
                FieldSchema f = byName.get(m.field());
                if (f == null) {
                    throw new IrisException(ErrorCode.INVALID_QUERY,
                            "聚合字段不存在: " + m.field());
                }
                if (!"numeric".equals(f.effectiveIndex())) {
                    throw new IrisException(ErrorCode.INVALID_QUERY,
                            "聚合字段 " + m.field() + " 不是 numeric 索引（当前 "
                                    + f.effectiveIndex() + "），无法做 " + m.op()
                                    + "；字符串字段只能做 count 分组计数");
                }
            }
            reduces.add(new RedisAdapter.AggregateReduce(m.op(), m.field(), m.alias()));
        }
        if (request.sortBy() != null) {
            boolean known = request.groupBy().contains(request.sortBy())
                    || request.metrics().stream().anyMatch(m -> m.alias().equals(request.sortBy()))
                    || byName.containsKey(request.sortBy());
            if (!known) {
                // 别名是派生的固定形态（Metric.alias()）：count→OP_COUNT、其余→op_field。
                // 报错把规则教给模型，别让它猜
                throw new IrisException(ErrorCode.INVALID_QUERY,
                        "排序别名 " + request.sortBy() + " 不是分组字段或聚合指标别名；"
                                + "合法别名：分组字段名，或固定指标别名 count / sum_<字段> / avg_<字段>"
                                + " / min_<字段> / max_<字段>（指标别名不可自定义，metrics 无 alias 参数）");
            }
        }
        // ---- 过滤条件下推（与 find() 的索引路径同一翻译器，语义逐条一致）----
        String query = SearchQueryTranslator.translate(
                request.filters(), request.rangeFilters(), request.textFilters(), schema);
        if (query == null) {
            throw new IrisException(ErrorCode.INVALID_QUERY,
                    "过滤条件包含未索引字段，无法下推聚合；请改用已索引字段过滤");
        }
        RedisAdapter.FtAggregateResult agg = redis.ftAggregate(indexName, query,
                request.groupBy(), reduces, request.sortBy(), request.sortDesc(), request.limit());
        // ---- 数值转换：数值型值转 Double/Long，其余保持字符串（JSON 友好）----
        List<Map<String, Object>> rows = new ArrayList<>(agg.rows().size());
        for (Map<String, String> raw : agg.rows()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : raw.entrySet()) {
                row.put(e.getKey(), toNumericIfPossible(e.getValue()));
            }
            rows.add(row);
        }
        return new AggregateResult(agg.totalGroups(), agg.totalGroups(), rows);
    }

    /**
     * 维度归并聚合：按 FK 分组聚合 → 维表投影翻译分组键 → 同维度值归并重算。
     *
     * <p><b>为什么归并前不做 SORTBY</b>：排序对象是 FK 组（如 2957 个配置实例），
     * 排完也会被归并打乱——排序只在归并后的维度组（如 8 大类）上有意义，在应用层做。
     *
     * <p><b>avg 的归并正确性</b>：FT.AGGREGATE 的 avg reduce 只返回均值，丢失 (sum, count)
     * 对——均值不可直接合并。归并路径把 avg 内部拆成 sum+count 两个 reduce，
     * 归并后重算 Σsum/Σcount（标准 partial aggregation 语义）。
     *
     * <p><b>正确性不靠缓存 TTL</b>：维表映射每次实时读投影（pipeline 几十 ms），
     * 无本地缓存——数据版本守卫在 dispatcher 层把维表声明为依赖，缓存装饰链负责失效。
     */
    private AggregateResult aggregateWithDimension(AggregateRequest request,
                                                   com.iris4j.context.model.DimensionPath dp) {
        if (request.dimensionPaths().size() > 1) {
            throw new IrisException(ErrorCode.INVALID_QUERY,
                    "v1 仅支持一个维度路径（当前 " + request.dimensionPaths().size()
                            + " 个）；多跳/多维归并在路线图 v2");
        }
        if (!indexManager.isQueryReady(request.namespace(), request.entity())) {
            throw new IrisException(ErrorCode.INVALID_QUERY,
                    "实体 " + request.entity() + " 的索引未就绪，聚合暂不可用（回填中或索引缺失）");
        }
        String indexName = keys.indexKey(request.namespace(), request.entity());
        EntitySchema factSchema = schemaProvider.get(request.namespace(), request.entity());
        Map<String, FieldSchema> factByName = new LinkedHashMap<>();
        for (FieldSchema f : factSchema.fields()) {
            factByName.put(f.name(), f);
        }
        // metrics 构造（avg 拆 sum+count 对）
        List<RedisAdapter.AggregateReduce> reduces = new ArrayList<>();
        // 记录 avg 指标：alias -> 合成别名对
        Map<String, String[]> avgPairs = new LinkedHashMap<>();
        for (AggregateRequest.Metric m : request.metrics()) {
            if (!OP_COUNT.equals(m.op())) {
                FieldSchema f = factByName.get(m.field());
                if (f == null) {
                    throw new IrisException(ErrorCode.INVALID_QUERY, "聚合字段不存在: " + m.field());
                }
                if (!"numeric".equals(f.effectiveIndex())) {
                    throw new IrisException(ErrorCode.INVALID_QUERY,
                            "聚合字段 " + m.field() + " 不是 numeric 索引（当前 " + f.effectiveIndex()
                                    + "），无法做 " + m.op());
                }
            }
            if (OP_AVG.equals(m.op())) {
                String sumAlias = "__p4_avg_sum_" + m.alias();
                String cntAlias = "__p4_avg_cnt_" + m.alias();
                reduces.add(new RedisAdapter.AggregateReduce("sum", m.field(), sumAlias));
                reduces.add(new RedisAdapter.AggregateReduce(OP_COUNT, null, cntAlias));
                avgPairs.put(m.alias(), new String[]{sumAlias, cntAlias});
            } else {
                reduces.add(new RedisAdapter.AggregateReduce(m.op(), m.field(), m.alias()));
            }
        }
        String query = SearchQueryTranslator.translate(
                request.filters(), request.rangeFilters(), request.textFilters(), factSchema);
        if (query == null) {
            throw new IrisException(ErrorCode.INVALID_QUERY,
                    "过滤条件包含未索引字段，无法下推聚合；请改用已索引字段过滤");
        }
        // 归并前聚合：按 fk 分组、无排序、limit=组数上限（超限在结果侧判 total 拒绝）
        RedisAdapter.FtAggregateResult agg = redis.ftAggregate(indexName, query,
                List.of(dp.fkField()), reduces, null, false, dimensionMaxInputGroups);
        if (agg.totalGroups() > dimensionMaxInputGroups) {
            throw new IrisException(ErrorCode.INVALID_QUERY,
                    "维度归并前分组数 " + agg.totalGroups() + " 超过上限 " + dimensionMaxInputGroups
                            + "；请增加过滤条件缩小范围后再聚合");
        }
        if (agg.rows().isEmpty()) {
            return new AggregateResult(0, 0, List.of());
        }

        // ---- 批量拉维表映射：fk 值 -> 维度值（只取单字段 path，省 95% 网络负载）----
        Map<String, String> mapping = new LinkedHashMap<>();
        List<String> pendingFks = new ArrayList<>();
        for (Map<String, String> raw : agg.rows()) {
            String fk = raw.get(dp.fkField());
            if (fk != null) {
                pendingFks.add(fk);
            }
        }
        for (int from = 0; from < pendingFks.size(); from += dimensionFetchBatch) {
            List<String> batch = pendingFks.subList(from, Math.min(from + dimensionFetchBatch, pendingFks.size()));
            List<String> batchKeys = batch.stream()
                    .map(fk -> keys.entityKey(request.namespace(), dp.viaEntity(), fk))
                    .toList();
            List<String> values = redis.jsonGetPathBatch(batchKeys, "$." + dp.dimField());
            for (int i = 0; i < batch.size(); i++) {
                String v = values.get(i);
                if (v == null || v.equals("null")) {
                    continue; // 维表行缺失或维度字段为 null → 走 UNKNOWN 策略
                }
                mapping.put(batch.get(i), stripJsonString(v));
            }
        }

        // ---- 归并重算：同维度值合并（count/sum 累加、avg 恢复 (sum,count)、min/max 极值）----
        Map<String, Map<String, MergeState>> grouped = new LinkedHashMap<>();
        for (Map<String, String> raw : agg.rows()) {
            String fk = raw.get(dp.fkField());
            String dim = mapping.get(fk);
            if (dim == null) {
                switch (dimensionUnknownGroup) {
                    case "discard" -> { continue; }
                    case "error" -> throw new IrisException(ErrorCode.INVALID_QUERY,
                            "维度映射缺失：" + dp.fkField() + "=" + fk + " 在维表 " + dp.viaEntity()
                                    + " 中不存在（unknown-group=error 策略）");
                    default -> dim = "UNKNOWN"; // keep（默认）
                }
            }
            Map<String, MergeState> states = grouped.computeIfAbsent(dim,
                    k -> new LinkedHashMap<>());
            for (AggregateRequest.Metric m : request.metrics()) {
                MergeState st = states.computeIfAbsent(m.alias(), k -> new MergeState());
                if (OP_AVG.equals(m.op())) {
                    String[] pair = avgPairs.get(m.alias());
                    st.avgAdd(parseDouble(raw.get(pair[0])), parseDouble(raw.get(pair[1])));
                } else if (OP_MIN.equals(m.op())) {
                    st.minCheck(parseDouble(raw.get(m.alias())));
                } else if (OP_MAX.equals(m.op())) {
                    st.maxCheck(parseDouble(raw.get(m.alias())));
                } else { // count / sum：归并 = 累加
                    st.sumAdd(parseDouble(raw.get(m.alias())));
                }
            }
        }

        // ---- 物化输出行：dimField 作为组键 + 各 metric 终值 ----
        List<Map<String, Object>> rows = new ArrayList<>(grouped.size());
        for (Map.Entry<String, Map<String, MergeState>> e : grouped.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(dp.dimField(), e.getKey());
            for (AggregateRequest.Metric m : request.metrics()) {
                row.put(m.alias(), e.getValue().get(m.alias()).result(m.op()));
            }
            rows.add(row);
        }
        // 归并后排序（sortBy = metric 别名；dim 字段名暂不支持作为排序键——v1 语义从简）
        if (request.sortBy() != null && !request.sortBy().equals(dp.dimField())) {
            boolean known = request.metrics().stream().anyMatch(m -> m.alias().equals(request.sortBy()));
            if (!known) {
                throw new IrisException(ErrorCode.INVALID_QUERY,
                        "排序别名 " + request.sortBy()
                                + " 在维度归并场景只能是指标别名（如 count / sum_<字段>）或维度字段 "
                                + dp.dimField());
            }
            Comparator<Map<String, Object>> cmp = Comparator.comparing(
                    r -> r.get(request.sortBy()), this::compareSortValues);
            if (request.sortDesc()) {
                cmp = cmp.reversed();
            }
            rows.sort(cmp);
        }
        List<Map<String, Object>> limited = rows.size() > request.limit()
                ? rows.subList(0, request.limit()) : rows;
        return new AggregateResult(grouped.size(), agg.totalGroups(), limited);
    }

    /**
     * JSON.GET {@code $} path 返回值集数组包裹（如 {@code ["WECHAT"]}）——剥到裸标量。
     * 数字维度（{@code [3]}）同理；多层引号（{@code ["\"x\""]}）按一层剥（维度值含引号属脏数据，非目标场景）。
     */
    private static String stripJsonString(String v) {
        if (v.length() >= 2 && v.charAt(0) == '[' && v.charAt(v.length() - 1) == ']') {
            v = v.substring(1, v.length() - 1);
        }
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static Double parseDouble(String v) {
        if (v == null) {
            return null;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

        /** 维度归并中间态（partial aggregation）：count/sum 累加、avg 记 (sum,count)、min/max 极值。 */
        private static final class MergeState {
            private double sum;
            private long n;
            private double min = Double.NaN;
            private double max = Double.NaN;

            void sumAdd(Double v) {
                if (v != null) {
                    sum += v;
                }
            }

            void avgAdd(Double s, Double c) {
                if (s != null) {
                    sum += s;
                }
                if (c != null) {
                    n += c.longValue();
                }
            }

            void minCheck(Double v) {
                if (v != null && (Double.isNaN(min) || v < min)) {
                    min = v;
                }
            }

            void maxCheck(Double v) {
                if (v != null && (Double.isNaN(max) || v > max)) {
                    max = v;
                }
            }

            Object result(String op) {
                double d = switch (op) {
                    case OP_AVG -> n == 0 ? 0 : sum / n;
                    case OP_MIN -> min;
                    case OP_MAX -> max;
                    default -> sum; // count / sum
                };
                // count 为整数语义。注意不能用三元 `cond ? (long) d : d`——两分支
                // 数值提升后整个表达式变 double，Long 分支也会带 .0（易错点）
                if (OP_COUNT.equals(op)) {
                    return (long) d;
                }
                return d;
            }
        }

    /** 字符串数值转 Long/Double；不可解析保持原样（分组键多为字符串）。 */
    private Object toNumericIfPossible(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            // fall through to double
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    /**
     * 排序值比较：两侧都能解析为数值时按数值比（JSON 反序列化的 Integer/Long/Double
     * 与字符串形态数字统一处理），否则按字符串比。null 排最后（升序语境），
     * DESC 由 reversed() 反转后会排最前——与主流引擎「空值视作最小」的直觉相反，
     * 但语义自洽且文档化。
     */
    private int compareSortValues(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        Double da = asDouble(a);
        Double db = asDouble(b);
        if (da != null && db != null) {
            return Double.compare(da, db);
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    /** 数值提取：Number 直接取值；字符串可解析为数字时转数字（排序语义与索引路径一致）。 */
    private Double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 内存过滤（SCAN 降级路径），与 {@link SearchQueryTranslator} 语义逐条对齐：
     * 单值 = equals；集合值（access tags）= 任一命中（"IN" 语义）。
     * 两边语义不一致会导致同一查询在 FT 路径和 SCAN 路径返回不同结果，
     * 这是隔离语义正确性的底线。
     *
     * <p><b>用字符串比较而非对象 equals</b>：投影里的值来自 JSON 反序列化，
     * 类型不一定与过滤值一致（如 filters 传 Integer 1001、投影里是 Long 1001）。
     * 统一 {@code String.valueOf} 后比较，避免类型差异导致漏匹配。
     */
    private boolean matches(Map<String, Object> data, Map<String, Object> filters) {
        for (Map.Entry<String, Object> f : filters.entrySet()) {
            Object actual = data.get(f.getKey());
            if (actual == null) {
                return false;
            }
            if (f.getValue() instanceof Collection<?> expected) {
                boolean hit = false;
                for (Object v : expected) {
                    if (String.valueOf(v).equals(String.valueOf(actual))) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    return false;
                }
            } else if (!String.valueOf(f.getValue()).equals(String.valueOf(actual))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 反序列化投影 JSON。
     *
     * @throws IllegalStateException 解析失败时抛出。投影 JSON 由本类自己写入，
     *                               解析失败说明数据被外部污染，属于必须暴露的严重问题。
     */
    private Map<String, Object> readJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("投影 JSON 解析失败: " + json, e);
        }
    }
}

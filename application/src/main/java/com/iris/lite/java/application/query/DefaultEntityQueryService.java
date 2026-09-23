package com.iris.lite.java.application.query;

import com.iris.lite.java.context.model.AggregateRequest;
import com.iris.lite.java.context.model.AggregateResult;
import com.iris.lite.java.context.model.EntityProjection;
import com.iris.lite.java.context.model.ProjectionPage;
import com.iris.lite.java.context.model.QueryRequest;
import com.iris.lite.java.context.repository.EntityProjectionRepository;
import com.iris.lite.java.context.schema.EntitySchema;
import com.iris.lite.java.context.schema.SchemaProvider;
import com.iris.lite.java.shared.error.ErrorCode;
import com.iris.lite.java.shared.error.IrisException;
import com.iris.lite.java.shared.metrics.IrisMetrics;
import com.iris.lite.java.shared.model.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体查询服务的默认实现（查询链路最内层，真正干活的一层）。
 *
 * <p><b>职责</b>：编排 Schema 校验、租户隔离注入、仓储检索、字段裁剪与分页。
 * 不包含任何 Redis 命令与传输细节。
 *
 * <p><b>在装饰器链中的位置（四层）</b>：
 * {@code AccessControlledEntityQueryService -> SemanticCachedEntityQueryService
 * -> CachedEntityQueryService -> 本类}。
 * 本类是唯一真正访问仓储的一层，上面三层分别是访问控制与两级缓存装饰器。
 *
 * <p><b>为什么用 {@code @Service("delegateQueryService")} 显式命名</b>：
 * 容器里有五个 {@link EntityQueryService} 实现，靠 bean 名做精确装配，
 * 避免 {@code @Primary} 的隐含顺序依赖——装饰器链的组装顺序必须显式可控。
 */
@Service("delegateQueryService")
public class DefaultEntityQueryService implements EntityQueryService {

    private static final Logger log = LoggerFactory.getLogger(DefaultEntityQueryService.class);

    private final EntityProjectionRepository repository;
    private final SchemaProvider schemaProvider;

    public DefaultEntityQueryService(
            EntityProjectionRepository repository,
            SchemaProvider schemaProvider) {
        this.repository = repository;
        this.schemaProvider = schemaProvider;
    }

    /**
     * 执行查询：Schema 校验 -> 租户隔离注入 -> 仓储检索（已分页）-> 字段裁剪。
     *
     * <p><b>为什么在这里抛"实体不存在"而不是返回空页</b>：
     * 主键精确查询查不到 = 调用方指定的那个实体确实不存在，属于明确的错误，
     * 返回空列表会让调用方误以为"查到了但没有数据"。
     * 而等值过滤查不到是正常结果（条件本来就可能无匹配），返回空列表。
     */
    @Override
    public Page<Map<String, Object>> query(QueryRequest request) {
        // 查询耗时 Timer（tag 只带 entity，低基数）。放在最内层——
        // 计的是"真正干活"的耗时，缓存命中在上面两层就返回了，不会污染该指标
        return IrisMetrics.time("iris.query",
                () -> doQuery(request), "entity", request.entity());
    }

    /**
     * 服务端聚合：Schema 字段存在性校验 + 租户隔离注入后
     * 下推仓储（FT.AGGREGATE）。字段索引校验在仓储层（它持有字段索引形态推导），
     * 这里负责应用层语义：字段存在、租户必填、多租户冲突拒绝。
     */
    @Override
    public AggregateResult aggregate(AggregateRequest request) {
        return IrisMetrics.time("iris.aggregate",
                () -> doAggregate(request), "entity", request.entity());
    }

    private AggregateResult doAggregate(AggregateRequest request) {
        EntitySchema schema = schemaProvider.get(request.namespace(), request.entity());
        // 分组/聚合字段必须真实存在（索引形态校验在仓储层，fail-closed 同源）
        java.util.Set<String> fieldNames = schema.fields().stream()
                .map(com.iris.lite.java.context.schema.FieldSchema::name)
                .collect(java.util.stream.Collectors.toSet());
        for (String g : request.groupBy()) {
            if (!fieldNames.contains(g)) {
                throw new IrisException(ErrorCode.INVALID_QUERY,
                        "分组字段不存在: " + g + "（实体 " + request.entity() + "）");
            }
        }
        // 维度归并路径校验（fail-closed，报错即文档）：
        // ① fk 字段存在且声明了 relatedEntity；② relatedEntity 与路径实体精确一致；③ 维度字段存在于维表
        for (com.iris.lite.java.context.model.DimensionPath dp : request.dimensionPaths()) {
            com.iris.lite.java.context.schema.FieldSchema fk = schema.fields().stream()
                    .filter(f -> f.name().equals(dp.fkField()))
                    .findFirst()
                    .orElseThrow(() -> new IrisException(ErrorCode.INVALID_QUERY,
                            "维度路径的分组字段不存在: " + dp.fkField() + "（实体 " + request.entity() + "）"));
            if (fk.relatedEntity() == null || !fk.relatedEntity().equals(dp.viaEntity())) {
                throw new IrisException(ErrorCode.INVALID_QUERY,
                        "维度路径 " + dp.path() + " 与声明的关系不一致：字段 " + dp.fkField()
                                + " 的 relatedEntity 为 " + fk.relatedEntity()
                                + "，路径中的维表实体必须与之相同");
            }
            EntitySchema viaSchema = schemaProvider.get(request.namespace(), dp.viaEntity());
            if (viaSchema.fields().stream().noneMatch(f -> f.name().equals(dp.dimField()))) {
                throw new IrisException(ErrorCode.INVALID_QUERY,
                        "维度字段不存在: " + dp.viaEntity() + "." + dp.dimField()
                                + "（合法写法: fk字段->维表实体.维度字段，且维度字段须在维表 Schema 中声明）");
            }
        }
        for (AggregateRequest.Metric m : request.metrics()) {
            if (m.field() != null && !fieldNames.contains(m.field())) {
                throw new IrisException(ErrorCode.INVALID_QUERY,
                        "聚合字段不存在: " + m.field() + "（实体 " + request.entity() + "）");
            }
        }
        // 租户隔离：与 query 同一实现——多租户实体强制注入，filters 冲突拒绝；
        // 非多租户实体（tenantField=null）直接跳过，与 query 路径行为一致
        Map<String, Object> effectiveFilters = request.filters();
        if (schema.tenantField() != null) {
            if (request.tenant() == null) {
                throw new IrisException(ErrorCode.TENANT_REQUIRED,
                        "实体 " + request.entity() + " 为多租户实体，请在请求中携带 tenant");
            }
            effectiveFilters = mergeTenantScope(schema, request.filters(), request.tenant());
        }
        AggregateRequest effective = new AggregateRequest(request.namespace(), request.entity(),
                request.groupBy(), request.dimensionPaths(), request.metrics(), effectiveFilters,
                request.tenant(), request.agentTags(), request.rangeFilters(), request.textFilters(),
                request.sortBy(), request.sortDesc(), request.limit());
        return repository.aggregate(effective);
    }

    private Page<Map<String, Object>> doQuery(QueryRequest request) {
        // 1. 取 Schema 并校验请求字段合法性（含不存在的字段名报错）
        EntitySchema schema = schemaProvider.get(request.namespace(), request.entity());
        schema.validateFields(request.fields());

        // 2. 行级租户隔离：多租户实体强制注入租户过滤，调用方无法绕过或伪造
        QueryRequest effective = applyTenantScope(schema, request);

        // 3. 仓储检索（分页在存储侧下推：items 为本页结果，total 为匹配总数）
        ProjectionPage page = repository.find(effective);

        // 4. 主键精确查询但无结果 -> 实体不存在；等值过滤无结果 -> 空列表
        if (isPrimaryKeyLookup(schema, effective.filters()) && page.items().isEmpty()) {
            log.debug("主键查询无结果 ns={} entity={} filters={}",
                    request.namespace(), request.entity(), effective.filters());
            throw new IrisException(ErrorCode.ENTITY_NOT_FOUND,
                    "实体不存在: " + request.namespace() + "/" + request.entity());
        }

        // 5. 字段裁剪（items 已分页，不再做内存 subList——100 万条全量进堆会 OOM）
        List<Map<String, Object>> items = page.items().stream()
                .map(p -> project(p, request.fields()))
                .toList();

        log.debug("查询完成 ns={} entity={} total={} 返回={} page={} pageSize={} tenant={}",
                request.namespace(), request.entity(), page.total(), items.size(),
                request.page(), request.pageSize(), request.tenant());
        return Page.of(items, page.total(), request.page(), request.pageSize());
    }

    /**
     * 行级租户隔离：把租户条件强制并入过滤条件。
     *
     * <p><b>为什么要强制注入而不是让调用方自己传</b>：租户隔离是安全边界，
     * 不能指望每个调用方都记得传。注入后仓储层与缓存层无感知，
     * 且调用方<b>无法绕过</b>——即使构造特殊请求也只会命中自己租户的数据。
     *
     * <p><b>四种情形</b>：
     * <ul>
     *   <li>Schema 未声明 tenantField -> 非多租户实体，tenant 参数忽略；</li>
     *   <li>声明了 tenantField 但请求未带 tenant -> IRIS-1004 拒绝；</li>
     *   <li>filters 里显式带 tenantField 且与 tenant 不一致 -> IRIS-1005 拒绝（防伪造）；</li>
     *   <li>filters 里带的值与 tenant 一致 -> 原样通过（幂等，不重复注入）。</li>
     * </ul>
     */
    private QueryRequest applyTenantScope(EntitySchema schema, QueryRequest request) {
        String tenantField = schema.tenantField();
        // 非多租户实体：不做任何处理
        if (tenantField == null) {
            return request;
        }
        if (request.tenant() == null) {
            log.debug("多租户实体缺少 tenant，拒绝 ns={} entity={} tenantField={}",
                    request.namespace(), request.entity(), tenantField);
            throw new IrisException(ErrorCode.TENANT_REQUIRED,
                    "实体 " + request.entity() + " 为多租户实体，请在请求中携带 tenant");
        }
        Map<String, Object> merged = mergeTenantScope(schema, request.filters(), request.tenant());
        if (merged == request.filters()) {
            return request;
        }
        // 重建必须透传全部字段：agentTags/rangeFilters/textFilters/排序丢掉任何一个
        // 都会导致下游语义分叉（如缓存 key 不含范围条件，范围查询命中错缓存）
        return new QueryRequest(request.namespace(), request.entity(), request.fields(),
                merged, request.page(), request.pageSize(), request.tenant(), request.agentTags(),
                request.rangeFilters(), request.textFilters(),
                request.sortField(), request.sortDesc());
    }

    /**
     * 租户条件合并（{@link #applyTenantScope} 与聚合共用的原语）。
     *
     * <p><b>前置</b>：tenantField 非空（多租户实体）且 tenant 非空——调用方各自保证。
     *
     * @return 合并后的 filters；无需注入时原样返回调用方的 filters 引用（调用方据此跳过重建）
     */
    private Map<String, Object> mergeTenantScope(EntitySchema schema,
                                                 Map<String, Object> filters, String tenant) {
        String tenantField = schema.tenantField();
        Object explicit = filters.get(tenantField);
        // 显式传了租户过滤且与 tenant 参数不一致 —— 疑似越权伪造，直接拒绝。
        // 例外：filters 里的租户字段是集合值时，来自访问控制层的平台注入
        // （access tags 授权租户集合），语义是"tenant 必须落在授权集合内"——
        // 集合包含 tenant 即一致，不能按字符串相等误判为伪造
        if (explicit instanceof Collection<?> authorized) {
            if (!authorized.contains(tenant)) {
                log.warn("租户条件不在授权集合内，拒绝请求 entity={} tenant={} authorized={}",
                        schema.entity(), tenant, authorized);
                throw new IrisException(ErrorCode.TENANT_MISMATCH,
                        "tenant=" + tenant + " 不在授权范围 " + authorized + " 内");
            }
        } else if (explicit != null && !String.valueOf(explicit).equals(tenant)) {
            log.warn("租户条件冲突，拒绝请求 ns={} entity={} tenant={} filters.{}={}",
                    schema.namespace(), schema.entity(), tenant, tenantField, explicit);
            throw new IrisException(ErrorCode.TENANT_MISMATCH,
                    "filters." + tenantField + "=" + explicit + " 与 tenant=" + tenant + " 冲突");
        }
        // 值一致 = 调用方已显式带上，无需重复注入
        if (explicit != null) {
            return filters;
        }
        Map<String, Object> merged = new LinkedHashMap<>(filters);
        merged.put(tenantField, tenant);
        return merged;
    }

    /**
     * 判断是否为"主键精确查询"：filters 覆盖了全部主键字段。
     *
     * <p>空 filters 不算主键查询（那是全表扫描），所以有 {@code !filters.isEmpty()} 前置判断。
     *
     * <p>主键过滤值为集合（"IN 任一命中"语义）时是过滤查询而非精确定位——
     * 集合里任何一个 id 存在即可，空结果属正常命中为零，不该误报"实体不存在"。
     */
    private boolean isPrimaryKeyLookup(EntitySchema schema, Map<String, Object> filters) {
        if (filters.isEmpty()) {
            return false;
        }
        return schema.primaryKeys().stream().allMatch(pk ->
                filters.containsKey(pk)
                        && !(filters.get(pk) instanceof Collection<?>));
    }

    /**
     * 字段裁剪：只保留请求指定的字段。
     *
     * <p><b>用 LinkedHashMap 保持字段顺序</b>：返回给 Agent 的结果顺序应与请求一致，
     * 用 HashMap 会导致字段顺序随机，既影响可读性也让响应无法做稳定 diff。
     *
     * <p>fields 为空返回全部字段；投影中不存在的字段静默忽略（Schema 校验已提前拦截非法字段名）。
     */
    private Map<String, Object> project(EntityProjection projection, List<String> fields) {
        if (fields == null || fields.isEmpty()) {
            return new LinkedHashMap<>(projection.data());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String f : fields) {
            if (projection.data().containsKey(f)) {
                result.put(f, projection.data().get(f));
            }
        }
        return result;
    }
}

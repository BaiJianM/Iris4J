package com.iris.lite.application.query;

import com.iris.lite.context.model.AggregateRequest;
import com.iris.lite.context.model.AggregateResult;
import com.iris.lite.context.model.QueryRequest;
import com.iris.lite.context.schema.EntitySchema;
import com.iris.lite.context.schema.FieldSchema;
import com.iris.lite.context.schema.SchemaProvider;
import com.iris.lite.shared.error.ErrorCode;
import com.iris.lite.shared.error.IrisException;
import com.iris.lite.shared.model.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 访问控制装饰器（access tags）——查询链最外层的安全闸门。
 *
 * <p><b>语义</b>：每个 agent 一个 key + access tags 过滤可见数据。
 * 本层把 Agent 持有的 tags 落到两个维度：
 * <ul>
 *   <li><b>行级</b>：Schema 声明 {@code accessTagField} 时，把 Agent 的 tags
 *       作为 {@code @field:{tag1|tag2}} 条件强制注入 filters——行过滤下推到
 *       Query Engine 索引，与租户隔离同级别地不可绕过；</li>
 *   <li><b>字段级</b>：Schema 字段声明 {@code tags} 时，结果行中 Agent
 *       未授权的字段被静默剔除（"敏感字段默认不可见"）。</li>
 * </ul>
 *
 * <p><b>为什么在缓存层外面而不是里面</b>：行级注入发生在进入语义缓存之前，
 * tags 不同 → 注入的 filters 不同 → 缓存 key 不同 → 不同权限 Agent 的
 * 语义条目天然隔离，不存在"低权限 Agent 命中高权限 Agent 的缓存"的越权通道；
 * 字段裁剪发生在缓存返回之后——缓存里存的是完整行，裁剪只是投影，不污染缓存。
 *
 * <p><b>失败关闭（fail-closed）</b>：声明了 accessTagField 的实体，
 * 无 tags 的请求返回空页而非全量——权限系统的默认方向必须是"看不见"。
 */
@Service
@Primary
public class AccessControlledEntityQueryService implements EntityQueryService {

    private static final Logger log = LoggerFactory.getLogger(AccessControlledEntityQueryService.class);

    private final EntityQueryService delegate;
    private final SchemaProvider schemaProvider;

    public AccessControlledEntityQueryService(
            @Qualifier("semanticCachedQueryService")
            EntityQueryService delegate,
            SchemaProvider schemaProvider) {
        this.delegate = delegate;
        this.schemaProvider = schemaProvider;
    }

    @Override
    public Page<Map<String, Object>> query(QueryRequest request) {
        EntitySchema schema = schemaProvider.get(request.namespace(), request.entity());
        Set<String> tags = Set.copyOf(request.agentTags());
        boolean unrestricted = tags.contains("*");

        // ---------- 行级：注入 accessTagField 条件（原语与 aggregate 共用） ----------
        Map<String, Object> injected = injectRowScope(schema, tags, request.filters(),
                request.tenant(), "query");
        if (injected == null) {
            return new Page<>(List.of(), 0L, request.page(), request.pageSize());
        }
        QueryRequest effective = injected == request.filters() ? request
                : new QueryRequest(request.namespace(), request.entity(), request.fields(),
                        injected, request.page(), request.pageSize(), request.tenant(),
                        request.agentTags(), request.rangeFilters(), request.textFilters(),
                        request.sortField(), request.sortDesc(), request.fresh());

        Page<Map<String, Object>> result = delegate.query(effective);

        // ---------- 字段级：结果行剔除未授权字段（通配身份跳过） ----------
        List<String> protectedFields = unrestricted ? List.of() : schema.fields().stream()
                .filter(f -> !f.tags().isEmpty())
                .map(FieldSchema::name)
                .toList();
        if (protectedFields.isEmpty() || result.items().isEmpty()) {
            return result;
        }
        List<String> hidden = protectedFields.stream()
                .filter(name -> !schema.isFieldVisible(name, tags))
                .toList();
        if (hidden.isEmpty()) {
            return result;
        }
        List<Map<String, Object>> filtered = result.items().stream()
                .map(row -> stripHidden(row, hidden))
                .toList();
        log.debug("访问控制字段裁剪 ns={} entity={} 隐藏字段={} 行数={}",
                request.namespace(), request.entity(), hidden, filtered.size());
        return new Page<>(filtered, result.total(), result.page(), result.pageSize());
    }

    /**
     * 聚合的访问控制：与 {@link #query} 同级别的安全语义。
     *
     * <p><b>行级</b>：accessTagField 条件注入聚合的 filters（fail-closed 同款：
     * 行级保护实体 + 无 tags = 空结果），tags 不同 → 注入条件不同 → 聚合范围不同，
     * 不存在「低权限 Agent 聚合出高权限数据」的越权通道；
     * <b>字段级</b>：groupBy / metric.field / sortBy 落在 tags 保护字段上时
     * 直接拒绝——聚合结果虽然不带原始行，但「按敏感字段分组」本身就是敏感信息的
     * 泄露通道（如按手机号分组等价于枚举手机号），必须在入口拒绝。
     */
    @Override
    public AggregateResult aggregate(AggregateRequest request) {
        EntitySchema schema = schemaProvider.get(request.namespace(), request.entity());
        Set<String> tags = Set.copyOf(request.agentTags());
        boolean unrestricted = tags.contains("*");

        // 字段级：聚合维度/指标字段必须全部可见（fail-closed：先校验再查询）
        if (!unrestricted) {
            List<String> usedFields = new ArrayList<>(request.groupBy());
            request.metrics().stream()
                    .map(AggregateRequest.Metric::field)
                    .filter(Objects::nonNull)
                    .forEach(usedFields::add);
            if (request.sortBy() != null && !isMetricAlias(request.sortBy(), request.metrics())) {
                // sortBy 可能是 metric 别名（count / sum_amount 结果列）而非源字段——
                // 结果列排序不触碰源数据，无需可见性校验；只有源字段才做可见性校验
                usedFields.add(request.sortBy());
            }
            for (String f : usedFields) {
                if (!schema.isFieldVisible(f, tags)) {
                    log.debug("访问控制聚合字段拒绝 ns={} entity={} field={} tags={}",
                            request.namespace(), request.entity(), f, tags);
                    throw new IrisException(ErrorCode.INVALID_QUERY,
                            "字段 " + f + " 不在当前身份的可见范围内，无法用于聚合");
                }
            }
        }

        // 行级：accessTagField 条件注入（原语与 query 共用，交集语义见 injectRowScope）
        Map<String, Object> injected = injectRowScope(schema, tags, request.filters(),
                request.tenant(), "aggregate");
        if (injected == null) {
            return AggregateResult.empty();
        }
        AggregateRequest effective = injected == request.filters() ? request
                : new AggregateRequest(request.namespace(), request.entity(), request.groupBy(),
                        request.dimensionPaths(), request.metrics(), injected, request.tenant(),
                        request.agentTags(), request.rangeFilters(), request.textFilters(),
                        request.sortBy(), request.sortDesc(), request.limit());
        return delegate.aggregate(effective);
    }

    /**
     * 行级 access tags 条件注入——query 与 aggregate 共用的安全原语
     * （交集语义这类安全规则收敛在一份实现里，避免两份拷贝漂移出越权漏洞）。
     *
     * <p><b>安全条件覆盖同名字段</b>——调用方传入的 accessTagField 值不构成提权通道。
     *
     * <p><b>与该字段显式约束的交集语义（重要）</b>：当 accessTagField 上另有
     * 单值约束（典型：accessTagField = tenantField，tenant 参数就是约束）时，
     * 行必须"在授权集合内 **且** 满足显式约束"。若直接注入整个授权集合，
     * TAG 多值的 OR 语义会让"授权含 t1、请求 t2"的查询放行 t1 行（越权）。
     * 因此：显式约束 ∈ 授权集合 -> 收窄为单值 {constraint}；不在 -> fail-closed。
     *
     * @param path   日志路径标签（query / aggregate），fail-closed 时区分入口
     * @return null  = fail-closed 拒绝（调用方返回空结果）；
     *         入参 filters 原引用 = 无需注入（通配身份或非保护实体，调用方跳过重建，
     *         与 {@code DefaultEntityQueryService.mergeTenantScope} 的引用约定同款）；
     *         新 Map = 已注入（调用方以新 filters 重建请求）
     */
    private Map<String, Object> injectRowScope(EntitySchema schema, Set<String> tags,
                                               Map<String, Object> filters, String tenant,
                                               String path) {
        // 通配约定：tags 含 "*"（legacy 旧单 key 身份）不设限，跳过行级/字段级裁剪。
        // 不要给真实 Agent 配 "*"，否则该 Agent 绕过全部访问治理
        if (tags.contains("*") || schema.accessTagField() == null) {
            return filters;
        }
        if (tags.isEmpty()) {
            // fail-closed：行级保护实体 + 无任何 tag = 一行都不可见。
            // 不走查询直接返回空（注入空集合翻译不出合法查询串）
            log.debug("访问控制行级 fail-closed[{}] ns={} entity={} agent 无 tags",
                    path, schema.namespace(), schema.entity());
            return null;
        }
        String tagField = schema.accessTagField();
        Object constraint = filters.containsKey(tagField)
                ? filters.get(tagField)
                : (tagField.equals(schema.tenantField()) ? tenant : null);
        Map<String, Object> merged = new LinkedHashMap<>(filters);
        if (constraint != null) {
            String c = String.valueOf(constraint);
            if (!tags.contains(c)) {
                log.debug("访问控制行级 fail-closed[{}] ns={} entity={} {}={} 不在授权 tags {} 内",
                        path, schema.namespace(), schema.entity(), tagField, c, tags);
                return null;
            }
            merged.put(tagField, List.of(c));
        } else {
            merged.put(tagField, List.copyOf(tags));
        }
        return merged;
    }

    /** sortBy 是否为本次请求 metrics 的结果列别名（count / sum_amount 形态）。 */
    private static boolean isMetricAlias(String sortBy, List<AggregateRequest.Metric> metrics) {
        for (AggregateRequest.Metric m : metrics) {
            if (m.alias().equals(sortBy)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 剔除单行中的隐藏字段；无隐藏字段时原样返回（省一次拷贝）。
     *
     * <p><b>用 LinkedHashMap 保持字段顺序</b>：与 {@code DefaultEntityQueryService.project}
     * 同一条设计约定——HashMap 会让被裁剪行的字段顺序随机化，而未裁剪行有序，
     * 同一实体两种行的字段顺序不一致，响应无法稳定 diff。
     */
    private Map<String, Object> stripHidden(Map<String, Object> row, List<String> hidden) {
        boolean contains = hidden.stream().anyMatch(row::containsKey);
        if (!contains) {
            return row;
        }
        Map<String, Object> copy = new LinkedHashMap<>(row);
        hidden.forEach(copy::remove);
        return copy;
    }
}

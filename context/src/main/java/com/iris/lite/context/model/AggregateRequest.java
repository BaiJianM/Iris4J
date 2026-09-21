package com.iris.lite.context.model;

import com.iris.lite.shared.error.ErrorCode;
import com.iris.lite.shared.error.IrisException;

import java.util.List;
import java.util.Map;

/**
 * 统一聚合请求：REST / Agent / MCP 共用的服务端聚合原语。
 *
 * <p><b>能力边界</b>：GROUPBY 分组 + COUNT/SUM/AVG/MIN/MAX 归约 + 组内排序 TopN。
 * 聚合在 Redis Query Engine（FT.AGGREGATE）服务端完成，不把明细拉进模型上下文——
 * 这是「每渠道多少笔」类统计问题从 ≥6 轮穷举失败压缩到 1 轮出结果的治本解。
 *
 * <p><b>约束（fail-closed）</b>：groupBy / sortBy / 数值 metric 的字段必须是<b>已索引</b>字段
 * （groupBy/sortBy 任意索引形态，sum/avg/min/max 必须 numeric）——未索引字段直接拒绝，
 * 绝不降级 SCAN 全库聚合（请求路径全 keyspace 扫描是本项目红线）。
 *
 * <p><b>归一化</b>：与 {@link QueryRequest} 同风格——null 集合转空、limit 钳到
 * [1,{@link #MAX_LIMIT}]、op 小写化、空白 sortBy 转 null。
 *
 * @param namespace      命名空间
 * @param entity         实体名
 * @param groupBy        本实体分组字段（不含维度路径；可空 = 全局单组或纯维度归并）
 * @param dimensionPaths 维度归并路径（来自 group_by 项 "fk->entity.dim" 语法；可空）
 * @param metrics        归约指标清单（可空 = 只按 groupBy 去重计数场景由调用方显式传 COUNT）
 * @param filters      等值过滤（字段名 -> 单值或集合值=任一命中），与 query_entity 同语义
 * @param tenant       租户上下文；多租户实体必填（查询链强制注入，不可绕过）
 * @param agentTags    access tags（安全上下文，只能由鉴权层注入）
 * @param rangeFilters 数值范围过滤（字段名 -> 范围）
 * @param textFilters  TEXT 全文匹配（字段名 -> 文本）
 * @param sortBy       排序依据：metric 别名（如 count / sum_amount）或 groupBy 字段名；可空
 * @param sortDesc     是否降序（TopN 场景通常 true）
 * @param limit        返回组数上限（TopN 的 N）
 */
public record AggregateRequest(
        String namespace,
        String entity,
        List<String> groupBy,
        List<DimensionPath> dimensionPaths,
        List<Metric> metrics,
        Map<String, Object> filters,
        String tenant,
        List<String> agentTags,
        Map<String, RangeFilter> rangeFilters,
        Map<String, String> textFilters,
        String sortBy,
        boolean sortDesc,
        int limit) {

    /** 单次聚合返回的组数上限：防一次调用回灌超大结果（与结果裁剪同一目的）。 */
    public static final int MAX_LIMIT = 100;

    /**
     * 归约指标。op ∈ count|sum|avg|min|max；count 不需要 field，其余必须带 field
     * （且字段须为 numeric 索引，校验在查询链）。
     */
    public record Metric(String op, String field) {

        public Metric {
            if (op == null || op.isBlank()) {
                throw new IrisException(ErrorCode.INVALID_QUERY, "metric.op 不能为空");
            }
            op = op.trim().toLowerCase();
            if (!List.of("count", "sum", "avg", "min", "max").contains(op)) {
                throw new IrisException(ErrorCode.INVALID_QUERY,
                        "不支持的聚合操作: " + op + "（可选 count/sum/avg/min/max）");
            }
            if (!"count".equals(op) && (field == null || field.isBlank())) {
                throw new IrisException(ErrorCode.INVALID_QUERY,
                        "聚合操作 " + op + " 必须指定 field");
            }
            if (field != null && field.isBlank()) {
                field = null;
            }
        }

        /** 归约结果的别名（GROUPBY 管道内唯一）：count / sum_amount / avg_price 形态。 */
        public String alias() {
            return "count".equals(op) ? "count" : op + "_" + field;
        }
    }

    public AggregateRequest {
        if (namespace == null || namespace.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "namespace 不能为空");
        }
        if (entity == null || entity.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "entity 不能为空");
        }
        if (groupBy == null) {
            groupBy = List.of();
        }
        // 维度归并：group_by 项按语法分流——"fk->entity.dim" 进 dimensionPaths，
        // 普通字段留在 groupBy（FT.AGGREGATE 的 GROUPBY 子句只认本实体字段）。
        // 注意：调用链会二次构造（校验层重建 effective），此时入参 groupBy 已分流为
        // 普通字段、dimensionPaths 已携带结果——必须保留入参 paths，不能被当前
        // 分流（恒为空）覆盖——否则归并请求会在链上静默退化
        List<DimensionPath> paths = new java.util.ArrayList<>(
                dimensionPaths == null ? List.of() : dimensionPaths);
        List<String> plain = new java.util.ArrayList<>();
        for (String g : groupBy) {
            DimensionPath p = DimensionPath.parse(g);
            if (p != null) {
                paths.add(p);
            } else {
                plain.add(g);
            }
        }
        groupBy = List.copyOf(plain);
        dimensionPaths = List.copyOf(paths);
        if (metrics == null || metrics.isEmpty()) {
            // 缺省指标 = 计数（与 SQL 裸 GROUPBY + COUNT(*) 的直觉一致）
            metrics = List.of(new Metric("count", null));
        }
        if (filters == null) {
            filters = Map.of();
        }
        if (agentTags == null) {
            agentTags = List.of();
        }
        if (rangeFilters == null) {
            rangeFilters = Map.of();
        }
        if (textFilters == null) {
            textFilters = Map.of();
        }
        if (sortBy != null && sortBy.isBlank()) {
            sortBy = null;
        }
        if (limit < 1) {
            limit = 20;
        }
        if (limit > MAX_LIMIT) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "limit 超过上限 " + MAX_LIMIT);
        }
        if (tenant != null && tenant.isBlank()) {
            tenant = null;
        }
    }
}

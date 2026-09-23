package com.iris4j.context.model;

import com.iris4j.shared.error.ErrorCode;
import com.iris4j.shared.error.IrisException;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 统一查询请求，REST 与 MCP 共用。
 *
 * <p><b>能力边界</b>：主键精确查询、字段等值过滤（含集合值任一命中）、
 * 数值范围过滤（{@link RangeFilter}）、
 * TEXT 全文匹配（{@code textFilters}）。
 * 排序、聚合不在范围内。等值/范围/文本过滤都能下推到 Query Engine 索引，
 * 下推不可用时才降级 SCAN（内存语义同步支持，结果一致）。
 *
 * <p><b>紧凑构造器做归一化</b>：null 集合转空集合、非法页码回落默认、
 * 空字符串租户转 null。归一化后下游所有代码都不必再判空，
 * 且"请求没传"与"显式传了空"被统一成同一种状态，避免语义分叉。
 *
 * @param namespace    命名空间
 * @param entity       实体名
 * @param fields       要返回的字段；为空表示返回全部字段
 * @param filters      等值过滤条件（字段名 -> 值；支持集合值 = 任一命中）
 * @param page         页码，从 1 开始
 * @param pageSize     每页大小
 * @param tenant       租户上下文；多租户实体（Schema 声明 tenantField）必填，非多租户实体忽略
 * @param agentTags    Agent 的 access tags。<b>安全上下文，只能由鉴权层注入</b>——
 *                     REST 从鉴权过滤器的 request attribute 取，REST DTO 不暴露该参数，
 *                     调用方伪造不了；MCP 工具回调同样从 request attribute 取
 * @param rangeFilters 数值范围过滤（字段名 -> 范围），可空。字段须声明 numeric 索引才可下推
 * @param textFilters  TEXT 全文匹配（字段名 -> 文本），可空。字段须声明 text 索引才可下推
 * @param sortField    排序字段，可空（缺省按主键升序，分页稳定）。索引字段才可下推引擎排序；
 *                     非索引/TEXT 字段自动降级 SCAN 内存排序（结果语义一致）
 * @param sortDesc     是否降序；sortField 为空时忽略
 * @param fresh        时效旁路标志：true 时语义/精确两层查询缓存
 *                     跳过读（仍允许回写），强制直达 Query Engine 取最新数据。
 *                     由 Agent 编排层对用户问题做时效词检测后经 ToolContext 注入，
 *                     普通调用方不传（false，缓存照常生效）
 */
public record QueryRequest(
        String namespace,
        String entity,
        List<String> fields,
        Map<String, Object> filters,
        int page,
        int pageSize,
        String tenant,
        List<String> agentTags,
        Map<String, RangeFilter> rangeFilters,
        Map<String, String> textFilters,
        String sortField,
        boolean sortDesc,
        boolean fresh) {

    /** 分页上限，与 {@code PageRequest.MAX_PAGE_SIZE} 保持一致。 */
    public static final int MAX_PAGE_SIZE = 200;

    /**
     * 等值过滤集合值（IN 语义）的长度上限。
     *
     * <p><b>为什么必须限</b>：集合值是「JOIN 降级成 IN 列表」的滥用入口——
     * 模型想绕过单表聚合的跨表局限时，会先拉几千个主表主键再塞进 IN 过滤
     * （标定场景下模型试图塞 4,462 个 order_id）。超长 IN 列表让请求体积
     * 膨胀、FT 查询退化、token 爆炸，结果还往往因为分页截断而错误。
     * 超限报错并提示替代路径（缩小范围/分批/改用聚合），把滥用挡在入口。
     */
    public static final int MAX_FILTER_IN_SIZE = 200;

    /** 兼容构造器：7 参形态（无 agentTags）。 */
    public QueryRequest(String namespace, String entity, List<String> fields,
                        Map<String, Object> filters, int page, int pageSize, String tenant) {
        this(namespace, entity, fields, filters, page, pageSize, tenant, null, null, null);
    }

    /** 兼容构造器：8 参形态（无 range/text 过滤）。 */
    public QueryRequest(String namespace, String entity, List<String> fields,
                        Map<String, Object> filters, int page, int pageSize, String tenant,
                        List<String> agentTags) {
        this(namespace, entity, fields, filters, page, pageSize, tenant, agentTags, null, null);
    }

    /** 兼容构造器：10 参形态（无排序）——存量调用点零改动。 */
    public QueryRequest(String namespace, String entity, List<String> fields,
                        Map<String, Object> filters, int page, int pageSize, String tenant,
                        List<String> agentTags, Map<String, RangeFilter> rangeFilters,
                        Map<String, String> textFilters) {
        this(namespace, entity, fields, filters, page, pageSize, tenant, agentTags,
                rangeFilters, textFilters, null, false);
    }

    /** 兼容构造器：11 参形态（无时效旁路）——fresh=false，缓存照常生效。 */
    public QueryRequest(String namespace, String entity, List<String> fields,
                        Map<String, Object> filters, int page, int pageSize, String tenant,
                        List<String> agentTags, Map<String, RangeFilter> rangeFilters,
                        Map<String, String> textFilters, String sortField, boolean sortDesc) {
        this(namespace, entity, fields, filters, page, pageSize, tenant, agentTags,
                rangeFilters, textFilters, sortField, sortDesc, false);
    }

    /**
     * 紧凑构造器：必填校验 + 可选参数归一化。
     *
     * <p>注意 pageSize 超限是<b>抛异常</b>而非截断——静默截断会让调用方
     * 误以为拿到了全部数据，属于隐蔽的错误放大。宁可报错让调用方显式改小。
     */
    public QueryRequest {
        // namespace/entity 是定位 Schema 的必需信息，缺失直接拒绝
        if (namespace == null || namespace.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "namespace 不能为空");
        }
        if (entity == null || entity.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "entity 不能为空");
        }
        // 归一化：null -> 不可变空集合，下游可无脑遍历
        if (fields == null) {
            fields = List.of();
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
        // 页码/页大小非法时回落默认值（调用方未传的场景），但超限必须报错
        if (page < 1) {
            page = 1;
        }
        if (pageSize < 1) {
            pageSize = 20;
        }
        if (pageSize > MAX_PAGE_SIZE) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "pageSize 超过上限 " + MAX_PAGE_SIZE);
        }
        // IN 列表硬限制（上限 200）：集合值超限报错而非截断——静默截断会把
        // "任一命中"悄悄变成"部分命中"，错误结果比失败危险。报错消息给出替代路径，
        // AI 调用方（模型）读到后能自行改用聚合/分批等正确姿势
        for (Map.Entry<String, Object> f : filters.entrySet()) {
            if (f.getValue() instanceof Collection<?> c && c.size() > MAX_FILTER_IN_SIZE) {
                throw new IrisException(ErrorCode.INVALID_QUERY,
                        "等值过滤 " + f.getKey() + " 的集合值长度 " + c.size()
                                + " 超过上限 " + MAX_FILTER_IN_SIZE
                                + "（超长 IN 列表会拖垮查询）。请缩小过滤范围、分批查询，"
                                + "或改用 aggregate_entity 做服务端聚合");
            }
        }
        // 空字符串租户视同"未传租户"，避免下游用 isBlank 判定时出现分叉
        if (tenant != null && tenant.isBlank()) {
            tenant = null;
        }
        // 排序字段空白视同"未传"（缺省主键升序）；sortDesc 在 sortField 为空时无意义，
        // 保留原值即可——下游以 sortField 为准
        if (sortField != null && sortField.isBlank()) {
            sortField = null;
        }
    }
}

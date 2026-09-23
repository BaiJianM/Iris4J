package com.iris.lite.java.api.dto;

import java.util.List;
import java.util.Map;

/**
 * REST 查询请求体（最小查询模型）。
 * entity 从路径参数取，故请求体不含 entity。
 *
 * @param tenant       租户上下文；多租户实体必填，非多租户实体忽略
 * @param rangeFilters 数值范围过滤（字段名 -> {min, max}），端点可缺省
 * @param textFilters  TEXT 全文匹配（字段名 -> 文本，多词 AND）
 * @param sortField    排序字段，可空（缺省主键升序）；索引字段下推引擎排序，
 *                     TEXT/非索引字段自动降级内存排序
 * @param sortDesc     是否降序；sortField 为空时忽略
 */
public record QueryRequestDto(
        String namespace,
        List<String> fields,
        Map<String, Object> filters,
        Integer page,
        Integer pageSize,
        String tenant,
        Map<String, RangeDto> rangeFilters,
        Map<String, String> textFilters,
        String sortField,
        Boolean sortDesc) {

    /**
     * 紧凑构造器：page/pageSize 缺省时兜底为 1/20。
     *
     * <p><b>为什么 page/pageSize 用装箱 {@code Integer} 而不是 {@code int}</b>：
     * 踩过的坑——用原始类型时，请求体没传这两个字段，Jackson 3 会把 null
     * 映射进原始类型，直接抛 MismatchedInputException，
     * 表现为"不传分页参数就 400"。装箱类型配合这里的兜底即可解决。
     *
     * <p>其它可缺省的数值字段同理，一律用装箱类型。
     */
    public QueryRequestDto {
        if (page == null) {
            page = 1;
        }
        if (pageSize == null) {
            pageSize = 20;
        }
    }

    /**
     * 范围条件 DTO：min/max 均可缺省（null = 开放端点）。
     * 端点用 Object：数字毫秒或日期字面量字符串均可
     * （{@link RangeFilter#parse} 统一按 UTC 字面换算标准）。
     * 用独立 record 而不是裸 Map 是为了类型安全 + RangeFilter 里的 min<=max 校验可复用。
     */
    public record RangeDto(Object min, Object max) {
    }
}

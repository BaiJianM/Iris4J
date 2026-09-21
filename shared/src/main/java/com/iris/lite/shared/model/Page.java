package com.iris.lite.shared.model;

import java.util.List;

/**
 * 分页结果。REST 与 MCP 共用的返回结构。
 *
 * <p><b>为什么 total 是装箱 {@code Long} 而不是 {@code long}</b>：
 * 等值过滤查询走 SCAN 时，统计总数需要遍历全部匹配项，代价与分页裁切重复。
 * 允许 total 为 null 表示"未统计总数"，前端据此决定是否展示总页数。
 *
 * @param items    当前页数据（已按 page/pageSize 裁切）
 * @param total    命中的总记录数（可为 null 表示未统计）
 * @param page     当前页码，从 1 开始
 * @param pageSize 每页大小
 */
public record Page<T>(List<T> items, Long total, int page, int pageSize) {

    /**
     * 构造分页结果。
     *
     * <p>{@code items} 必须是已裁切好的当前页数据——分页裁切发生在调用方
     * （{@code DefaultEntityQueryService}），本类不做任何裁切逻辑，保持纯数据载体。
     */
    public static <T> Page<T> of(List<T> items, long total, int page, int pageSize) {
        return new Page<>(items, total, page, pageSize);
    }
}

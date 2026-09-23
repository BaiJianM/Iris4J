package com.iris.lite.java.context.model;

import java.util.List;

/**
 * 投影查询的分页结果载体（分页下推）。
 *
 * <p><b>为什么由仓储层返回 total</b>：分页下推后（FT.SEARCH LIMIT / 主键路径天然单行），
 * 仓储层在服务端就知道匹配总数，应用层再想算 total 就得再发一次全量查询——
 * 那是改造前 SCAN 路径的老路。total 随 items 一起出来，一次往返搞定。
 *
 * <p><b>与 Page 的分工</b>：本载体是仓储层的"原始分页结果"（未裁剪字段），
 * {@code Page} 是应用层裁剪字段后的对外结果。缓存层缓存的是裁剪后的 Page，
 * 两者不混用。
 *
 * @param items 本页命中的投影列表（可能为空列表，不为 null）
 * @param total 匹配总数（不受分页窗口影响）
 */
public record ProjectionPage(List<EntityProjection> items, long total) {

    public ProjectionPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** 空结果便捷工厂。 */
    public static ProjectionPage empty() {
        return new ProjectionPage(List.of(), 0);
    }
}

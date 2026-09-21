package com.iris.lite.context.schema;

/**
 * Schema 热加载状态快照。
 *
 * <p><b>用途</b>：这是运维排查"为什么我改的 Schema 没生效"的第一个抓手。
 * {@code hotReloadEnabled=false} 说明开关没开；{@code lastError} 非空说明
 * 最近一次加载/重载失败且已保留旧 Schema；{@code lastReloadAtMillis} 配合
 * {@code schemaCount} 能确认改动是否真的被吃到。
 *
 * @param hotReloadEnabled    是否启用外部目录热加载（开关 AND 目录已配置）
 * @param schemaDir           外部 Schema 目录（未配置时为空字符串）
 * @param schemaCount         当前生效的 Schema 总数（classpath 基线 + 外部覆盖合并去重）
 * @param lastReloadAtMillis  最近一次成功重载的 epoch 毫秒（启动加载也算一次）
 * @param lastError           最近一次加载错误（成功时为空）
 */
public record SchemaStatus(
        boolean hotReloadEnabled,
        String schemaDir,
        int schemaCount,
        long lastReloadAtMillis,
        String lastError) {
}

package com.iris4j.application.query;

/**
 * 语义缓存条目：结构化查询中「可模糊匹配的 STRING 过滤值文本」+ 其向量 + 查询结果。
 *
 * <p><b>硬约束部分不进条目</b>：entity/tenant/fields/分页/非字符串过滤值/过滤 key 集合
 * 由缓存 key 中的 hardKey 段精确匹配；本条目只承载值级相似度所需的信息。
 * 这样"结构不同的查询"根本不会成为候选，从机制上避免了误命中。
 *
 * <p><b>fingerprint 字段的作用</b>：记录条目所属的向量空间（Embedder 指纹）。
 * 换模型/维度后新旧条目的向量不可比，重嵌入服务据此识别待迁移的条目。
 * 缺失该字段的条目反序列化为 null，重嵌入服务统一按"待迁移"处理。
 *
 * <p><b>createdAt 字段</b>：便于观测条目年龄，排查"为什么一直在用旧结果"时有用。
 *
 * @param filtersText 可模糊匹配的字符串过滤值（如 "上海;VIP"）
 * @param vector      filtersText 的 L2 归一化向量
 * @param result      该查询对应的完整结果快照
 * @param fingerprint 生成向量时的 Embedder 指纹
 * @param createdAt   条目创建时间（ISO-8601）
 */
public record SemanticCacheEntry(
        String filtersText,
        float[] vector,
        CachedQueryResult result,
        String fingerprint,
        String createdAt) {
}

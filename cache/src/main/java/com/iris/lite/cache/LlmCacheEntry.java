package com.iris.lite.cache;

import java.time.Instant;
import java.util.Map;

/**
 * LLM 响应语义缓存条目。
 *
 * <p>缓存的是"prompt → LLM response"对：相似 prompt 语义命中后直接返回已缓存的
 * 响应，跳过真实的 LLM 调用（降本估算标准：
 * 月省 = 月输出 token 成本 × 命中率）。
 *
 * @param entryId    条目标识 = {@code modelTag:promptHash}（与文档 key 后两段一致）
 * @param modelTag   model 的短哈希标识（null/空 model 用 "none"）；KNN 的 TAG 过滤段，
 *                   <b>model 维度只做精确匹配，绝不参与语义模糊</b>——不同模型的
 *                   响应不能互相命中
 * @param promptHash prompt 的 SHA-256 十六进制（精确命中路径的 key）
 * @param prompt     触发缓存的原始 prompt（KNN 命中后重算余弦相似度用）
 * @param response   缓存的 LLM 响应
 * @param model      原始模型名（展示用；检索维度用 modelTag）
 * @param createdAt  写入时间（ISO 8601）
 * @param dependencies 依赖实体 → 写入时的数据版本（数据版本守卫；
 *                     null/空 = 未声明依赖，命中后不做新鲜度校验）
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record LlmCacheEntry(
        String entryId,
        String modelTag,
        String promptHash,
        String prompt,
        String response,
        String model,
        String createdAt,
        Map<String, Long> dependencies) {

    /** 紧凑构造器：createdAt 缺省取当前时间。 */
    public LlmCacheEntry {
        if (createdAt == null || createdAt.isBlank()) {
            createdAt = Instant.now().toString();
        }
        if (modelTag == null || modelTag.isBlank()) {
            modelTag = "none";
        }
    }
}

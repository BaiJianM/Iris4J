package com.iris.lite.cache;

import java.util.List;
import java.util.Optional;

/**
 * LLM 响应语义缓存仓储端口。
 *
 * <p>实现层负责 key 拼接、JSON 编解码与向量写入；应用层只面对条目模型。
 * 向量由调用方（应用服务）通过 Embedder 算好后传入——与记忆仓储同构，
 * 仓储不依赖 Embedder。
 */
public interface LlmCacheRepository {

    /**
     * 写入/覆盖一条缓存。vector 为 null 时只写文档（无 Embedder 场景退化为精确命中）。
     * ttlSeconds 到期后文档与向量一起消失。
     */
    void save(String namespace, LlmCacheEntry entry, float[] vector, long ttlSeconds);

    /** 精确读取：按 modelTag + promptHash 定位文档；过期/不存在返回 empty。 */
    Optional<LlmCacheEntry> get(String namespace, String modelTag, String promptHash);

    /**
     * KNN 检索相似 prompt 的向量 key（应用层回捞条目后按服务端距离判阈值）。
     * modelTag 非空时作为 TAG 预过滤下推（@model:{tag}）——不同模型绝不互相命中。
     *
     * @return 带距离的 KNN 命中（按距离升序，distance = 1 - 余弦）；无 Embedder/索引不可用时返回空列表。
     *         distance 为 null 表示服务端未回传分数（调用方回落本地重算余弦）
     */
    List<ScoredKnn> searchKnn(String namespace, float[] queryVector, String modelTag, int limit);

    /** KNN 命中：vecKey + 服务端算好的距离（免去客户端对每候选重新 embedding）。 */
    record ScoredKnn(String vecKey, Double distance) {
    }

    /** 按 KNN 返回的向量 key 回捞条目；文档已过期/不存在返回 empty。 */
    Optional<LlmCacheEntry> getByVecKey(String namespace, String vecKey);

    /**
     * 词法（BM25）召回（RAG 多路召回通道）：返回按 BM25 相关度降序的
     * 文档 key 列表，配合 {@link #getByDocKey} 回捞。
     *
     * <p>词法是增强通道：索引未建/查询不可词项化/链路失败一律返回空列表，
     * 绝不影响稠密主路径。modelTag 参与 TAG 精确预过滤（与 KNN 同一红线：
     * 不同模型的响应绝不互相命中）。
     */
    default List<String> searchLexical(String namespace, String modelTag, String query, int limit) {
        return List.of();
    }

    /** 按词法召回的文档 key 回捞条目；过期/不存在返回 empty。 */
    default Optional<LlmCacheEntry> getByDocKey(String namespace, String docKey) {
        return Optional.empty();
    }

    /** namespace 下缓存文档总数（容量淘汰判断用；不含向量 key）。 */
    long count(String namespace);

    /**
     * 容量淘汰：裁到 keepEntries 以内，返回实际删除条数。
     * 淘汰顺序 = 剩余 TTL 最短优先（与查询语义缓存同策略）。
     */
    int evictTo(String namespace, int keepEntries);

    /** 清空 namespace 下全部缓存（文档 + 各指纹向量），返回删除的文档数。 */
    long clear(String namespace);
}

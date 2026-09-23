package com.iris.lite.java.shared.key;

import com.iris.lite.java.shared.model.EntityKey;

/**
 * Redis key 生成策略接口。
 *
 * <p><b>边界意义</b>：接口定义在 shared 层（最底层，谁都能依赖），实现放 shared 层同包。
 * 这样 api / application / context / cdc / infrastructure 任何模块都能生成 key，
 * 但都不必知道 key 长什么样。改命名规范 = 改实现，全系统生效。
 *
 * <p><b>反例（禁止）</b>：在任何业务类里写
 * {@code "iris:" + ns + ":entity:" + entity + ":" + pk}。
 * 这种散落的拼接一旦和规范脱节，表现是"数据写了但查不到"，排查成本极高。
 *
 * <p>缺省实现 {@link DefaultKeyStrategy} 为纯字符串拼接；后续切 Redis Cluster
 * 可替换为带 hash tag 的实现，业务层零改动。
 */
public interface KeyStrategy {

    /**
     * 实体投影 key。
     *
     * @param namespace  命名空间，隔离不同数据源/环境
     * @param entity     实体名
     * @param primaryKey 主键值（字符串化后的值）
     */
    String entityKey(String namespace, String entity, String primaryKey);

    /**
     * 实体投影 key（从 {@link EntityKey} 派生）。
     *
     * <p>默认方法，避免调用方反复拆 record 字段。
     */
    default String entityKey(EntityKey key) {
        return entityKey(key.namespace(), key.entity(), key.primaryKey());
    }

    /** CDC 数据流 key。source 为数据源标识（如 topic 名）。 */
    String streamKey(String namespace, String source);

    /** 实体 Schema key。 */
    String schemaKey(String namespace, String entity);

    /** 源消费位点 key。 */
    String sourcePositionKey(String namespace);

    /** 工作记忆 key（按 namespace + sessionId 隔离）。 */
    String workingMemoryKey(String namespace, String sessionId);

    /** 长期记忆 key（按 namespace + memoryId 隔离）。 */
    String longTermMemoryKey(String namespace, String memoryId);

    /**
     * 长期记忆条目索引 key：{@code iris:{ns}:memidx:long}，SET。
     *
     * <p>成员 = memoryId。关键词检索经本索引枚举记忆文档，替代全 keyspace SCAN
     * （请求路径禁止 O(全库) 遍历）。结构与回填约定见
     * {@link RedisKeyPatterns#LONG_TERM_MEMORY_INDEX}。
     */
    String longTermMemoryIndexKey(String namespace);

    /** 长期记忆索引回填标记 key：{@code iris:{ns}:memidx:long:backfilled}（SET NX 占位）。 */
    String longTermMemoryIndexBackfillKey(String namespace);

    /**
     * 长期记忆向量 key（T4 引入）：{@code iris:{ns}:memory:vec:{fpHash}:{memoryId}}。
     *
     * @param fingerprintHash Embedder 指纹短哈希（向量空间隔离段）
     * @param memoryId        与长期记忆 key 的 memoryId 一一对应
     */
    String memoryVectorKey(String namespace, String fingerprintHash, String memoryId);

    /**
     * 工作记忆向量 key：{@code iris:{ns}:memory:wm:{fpHash}:{sessionId}:{entryId}}
     * （工作记忆语义索引）。
     *
     * @param fingerprintHash Embedder 指纹短哈希（向量空间隔离段）
     * @param sessionId       所属会话（既是 key 段，也是索引里的 TAG 过滤值）
     * @param entryId         工作记忆条目 id（与会话文档中的条目一一对应）
     */
    String workingMemoryVectorKey(String namespace, String fingerprintHash, String sessionId, String entryId);

    /**
     * 工作记忆向量前缀：{@code iris:{ns}:memory:wm:{fpHash}:}（含尾部冒号）。
     * FT.CREATE 的 PREFIX 参数与清理 SCAN 的模式基础。
     */
    String workingMemoryVectorPrefix(String namespace, String fingerprintHash);

    /**
     * 工作记忆操作互斥锁 key：{@code iris:{ns}:memory:wlock:{sessionId}}。
     * 渐进摘要写回与追加交错时使用（SET NX PX 短锁）。
     */
    String workingMemoryLockKey(String namespace, String sessionId);

    /** 精确缓存 key（按 namespace + 业务 key 隔离）。业务 key 自带 entity 段以支持按实体失效。 */
    String cacheKey(String namespace, String key);

    /**
     * 实体缓存 key 索引：{@code iris:{ns}:cacheidx:{entity}}，SET 结构。
     *
     * <p>成员为该实体下所有缓存条目的相对 key 段。CDC 失效该实体缓存时读本集合
     * 逐个删除，把失效代价从 O(全 keyspace) 降到 O(该实体缓存条目数)。
     * 结构与 TTL 约定见 {@link RedisKeyPatterns#CACHE_INDEX}。
     */
    String cacheIndexKey(String namespace, String entity);

    /**
     * Query Engine 二级索引名（T1 引入）：{@code iris:{namespace}:index:{entity}}。
     *
     * <p>每个 (namespace, entity) 一个索引；PREFIX 段（覆盖哪些文档）由调用方
     * 从 {@link #entityKey(String, String, String)} 传空主键派生，同样不在业务侧拼。
     */
    String indexKey(String namespace, String entity);

    /**
     * 动态 agent key 注册表 key：{@code iris:security:agent-keys}。
     *
     * <p>全局唯一（鉴权身份不属于任何 namespace），HASH 结构见
     * {@link RedisKeyPatterns#SECURITY_AGENT_KEYS}。
     */
    String securityAgentKeysKey();

    /** LLM 响应缓存文档 key：{@code iris:{ns}:llmcache:{modelTag}:{promptHash}}。 */
    String llmCacheKey(String namespace, String modelTag, String promptHash);

    /** 实体数据版本计数器 key：{@code iris:{ns}:ver:{entity}}（数据版本守卫）。 */
    String entityVersionKey(String namespace, String entity);

    /**
     * 配方自进化草稿池 key：
     * {@code iris:{ns}:recipe-drafts}，list 结构，LPUSH+LTRIM 封顶的审核队列。
     */
    String recipeDraftKey(String namespace);

    /** LLM 响应缓存向量 key：{@code iris:{ns}:llmcache:vec:{fpHash}:{modelTag}:{promptHash}}。 */
    String llmCacheVectorKey(String namespace, String fpHash, String modelTag, String promptHash);

    /** LLM 响应缓存向量前缀（含尾部冒号）：{@code iris:{ns}:llmcache:vec:{fpHash}:}。 */
    String llmCacheVectorPrefix(String namespace, String fpHash);

    /**
     * LLM 响应缓存向量 SCAN 模式：{@code iris:{ns}:llmcache:vec:*}。
     *
     * <p>与前缀方法的差异：SCAN 模式不带尾部冒号，{@code *} 跨段匹配任意指纹的
     * 全部向量 key（清空/清扫跨指纹数据用）。
     *
     * <p><b>调用受限</b>：SCAN 代价与 keyspace 总量成正比（366 万键下约 7 秒），
     * 只允许在显式运维路径（{@code clear}）使用，<b>禁止进入请求路径</b>——
     * 条目统计/淘汰请改用 {@link #llmCacheIndexKey} / {@link #llmCacheVectorIndexKey}。
     */
    String llmCacheVectorScanPattern(String namespace);

    /**
     * LLM 响应缓存条目索引 key：{@code iris:{ns}:llmcache:idx}，ZSET。
     *
     * <p>member = 文档 key、score = 条目过期 epoch 毫秒。用于替代
     * {@code SCAN iris:{ns}:llmcache:*:*} 做条目计数（ZCARD）与按"最先过期"
     * 淘汰（ZRANGE）。
     */
    String llmCacheIndexKey(String namespace);

    /** LLM 响应缓存向量索引 key：{@code iris:{ns}:llmcache:vecidx}，ZSET，member = 向量 key。 */
    String llmCacheVectorIndexKey(String namespace);
}

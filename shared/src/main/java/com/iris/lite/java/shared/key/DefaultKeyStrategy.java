package com.iris.lite.java.shared.key;

/**
 * 默认 key 生成实现：纯字符串拼接，无 Redis 依赖、无外部状态。
 *
 * <p><b>为什么所有 key 都必须走这里</b>：Redis 里的数据是"按 key 约定"组织的——
 * CDC 往 {@code iris:demo:entity:customer:1001} 写，查询侧必须按同一个规则读。
 * 一旦某个模块自己拼字符串，改命名规范时就会出现"写侧改了、读侧没改"的静默丢数据。
 * 因此规范集中在 {@link RedisKeyPatterns}，生成逻辑集中在这里，业务模块只依赖
 * {@link KeyStrategy} 接口。
 *
 * <p><b>实现细节</b>：用 {@code String.formatted}（Java 15+ 实例方法）而非
 * {@code String.format} 静态方法，语义完全等价，但模板常量可直接内联，读起来更顺。
 *
 * <p><b>Cluster 演进位</b>：当前命名不含 hash tag（{@code {...}}），单机/主从部署没问题。
 * 若后续切 Redis Cluster，同一次事务要操作的多个 key 必须落在同一个 hash slot，
 * 届时需要改成带 hash tag 的实现（例如 {@code iris:{demo:customer}:entity:1001}）。
 * 因为业务层只认 {@link KeyStrategy} 接口，换实现零改动——这就是抽象本接口的价值。
 *
 * <p><b>不打日志</b>：本类纯内存字符串拼接，无 IO、无分支失败路径，
 * 打日志只会淹没真正有价值的流程节点信息。
 */
public class DefaultKeyStrategy implements KeyStrategy {

    /**
     * 实体投影 key：{@code iris:{namespace}:entity:{entity}:{primaryKey}}。
     *
     * <p>这是全系统读写最频繁的 key——CDC 投影写、主键查询读、SCAN 过滤都用它。
     * 过滤查询靠把 primaryKey 段换成 {@code *} 得到 SCAN 模式串。
     */
    @Override
    public String entityKey(String namespace, String entity, String primaryKey) {
        return RedisKeyPatterns.ENTITY.formatted(namespace, entity, primaryKey);
    }

    /**
     * CDC 数据流 key：{@code iris:{namespace}:stream:{source}}。
     *
     * <p>注意：实际部署时 Debezium Redis sink 写出的 stream 名 = topic 名
     * （如 {@code iris.iris_demo.customer}），不带 {@code iris:demo:stream:} 前缀。
     * 本方法生成的是"redis-iris-java 自己管理"的流命名；CDC 源真正读的 stream 名来自
     * 配置项 {@code iris.cdc.sources[].stream}，两者不是一回事，不要混淆。
     */
    @Override
    public String streamKey(String namespace, String source) {
        return RedisKeyPatterns.STREAM.formatted(namespace, source);
    }

    /** 实体 Schema key：{@code iris:{namespace}:schema:{entity}}。当前 Schema 存 YAML 文件，此 key 为后续 Redis 化预留。 */
    @Override
    public String schemaKey(String namespace, String entity) {
        return RedisKeyPatterns.SCHEMA.formatted(namespace, entity);
    }

    /** 源消费位点 key：{@code iris:{namespace}:meta:source-position}。当前位点由 Redis Stream 的 consumer group 托管，此 key 为自建位点预留。 */
    @Override
    public String sourcePositionKey(String namespace) {
        return RedisKeyPatterns.SOURCE_POSITION.formatted(namespace);
    }

    /**
     * 工作记忆 key：{@code iris:{namespace}:memory:working:{sessionId}}。
     *
     * <p>一个会话一个 key，值是 JSON 数组（追加时读-改-写整条），
     * 所以 sessionId 必须能唯一标识一次对话。
     */
    @Override
    public String workingMemoryKey(String namespace, String sessionId) {
        return RedisKeyPatterns.WORKING_MEMORY.formatted(namespace, sessionId);
    }

    /** 长期记忆 key：{@code iris:{namespace}:memory:long:{memoryId}}。一条记忆一个 key，memoryId 为 UUID。 */
    @Override
    public String longTermMemoryKey(String namespace, String memoryId) {
        return RedisKeyPatterns.LONG_TERM_MEMORY.formatted(namespace, memoryId);
    }

    /** 长期记忆条目索引：{@code iris:{ns}:memidx:long}（SET，成员 = memoryId）。 */
    @Override
    public String longTermMemoryIndexKey(String namespace) {
        return RedisKeyPatterns.LONG_TERM_MEMORY_INDEX.formatted(namespace);
    }

    /** 长期记忆索引回填标记：{@code iris:{ns}:memidx:long:backfilled}。 */
    @Override
    public String longTermMemoryIndexBackfillKey(String namespace) {
        return RedisKeyPatterns.LONG_TERM_MEMORY_INDEX_BACKFILL.formatted(namespace);
    }

    /**
     * 长期记忆向量 key：{@code iris:{namespace}:memory:vec:{fpHash}:{memoryId}}（T4 引入）。
     *
     * <p>指纹哈希段在 vector 与 long 之间多一层——同一 memoryId 不同向量空间各存各的，
     * 换 Embedder 后旧向量自然失联（不被新索引覆盖），不会出现维度混杂。
     */
    @Override
    public String memoryVectorKey(String namespace, String fingerprintHash, String memoryId) {
        return RedisKeyPatterns.MEMORY_VECTOR.formatted(namespace, fingerprintHash, memoryId);
    }

    /** 工作记忆向量 key：{@code iris:{ns}:memory:wm:{fpHash}:{sessionId}:{entryId}}。 */
    @Override
    public String workingMemoryVectorKey(String namespace, String fingerprintHash, String sessionId, String entryId) {
        return RedisKeyPatterns.WORKING_MEMORY_VECTOR.formatted(namespace, fingerprintHash, sessionId, entryId);
    }

    /** 工作记忆向量前缀：{@code iris:{ns}:memory:wm:{fpHash}:}（FT.CREATE PREFIX / SCAN 清理用）。 */
    @Override
    public String workingMemoryVectorPrefix(String namespace, String fingerprintHash) {
        return RedisKeyPatterns.WORKING_MEMORY_VECTOR_PREFIX.formatted(namespace, fingerprintHash);
    }

    /** 工作记忆互斥锁 key：{@code iris:{ns}:memory:wlock:{sessionId}}。 */
    @Override
    public String workingMemoryLockKey(String namespace, String sessionId) {
        return RedisKeyPatterns.WORKING_MEMORY_LOCK.formatted(namespace, sessionId);
    }

    /** LLM 响应缓存文档 key：{@code iris:{ns}:llmcache:{modelTag}:{promptHash}}。 */
    @Override
    public String llmCacheKey(String namespace, String modelTag, String promptHash) {
        return RedisKeyPatterns.LLM_CACHE.formatted(namespace, modelTag, promptHash);
    }

    /** 实体数据版本计数器 key：{@code iris:{ns}:ver:{entity}}（数据版本守卫）。 */
    @Override
    public String entityVersionKey(String namespace, String entity) {
        return RedisKeyPatterns.ENTITY_VERSION.formatted(namespace, entity);
    }

    /** 配方自进化草稿池 key：{@code iris:{ns}:recipe-drafts}（审核队列）。 */
    @Override
    public String recipeDraftKey(String namespace) {
        return RedisKeyPatterns.RECIPE_DRAFT.formatted(namespace);
    }

    /** LLM 响应缓存向量 key：{@code iris:{ns}:llmcache:vec:{fpHash}:{modelTag}:{promptHash}}。 */
    @Override
    public String llmCacheVectorKey(String namespace, String fpHash, String modelTag, String promptHash) {
        return RedisKeyPatterns.LLM_CACHE_VECTOR.formatted(namespace, fpHash, modelTag, promptHash);
    }

    /** LLM 响应缓存向量前缀（含尾部冒号）：{@code iris:{ns}:llmcache:vec:{fpHash}:}。 */
    @Override
    public String llmCacheVectorPrefix(String namespace, String fpHash) {
        return RedisKeyPatterns.LLM_CACHE_VECTOR_PREFIX.formatted(namespace, fpHash);
    }

    /** LLM 响应缓存向量 SCAN 模式（跨指纹，不带尾部冒号）：{@code iris:{ns}:llmcache:vec:*}。 */
    @Override
    public String llmCacheVectorScanPattern(String namespace) {
        return RedisKeyPatterns.LLM_CACHE_VECTOR_SCAN.formatted(namespace);
    }

    /** LLM 响应缓存条目索引 key：{@code iris:{ns}:llmcache:idx}（ZSET，score=过期毫秒）。 */
    @Override
    public String llmCacheIndexKey(String namespace) {
        return RedisKeyPatterns.LLM_CACHE_INDEX.formatted(namespace);
    }

    /** LLM 响应缓存向量索引 key：{@code iris:{ns}:llmcache:vecidx}（ZSET）。 */
    @Override
    public String llmCacheVectorIndexKey(String namespace) {
        return RedisKeyPatterns.LLM_CACHE_VECTOR_INDEX.formatted(namespace);
    }

    /**
     * 精确缓存 key：{@code iris:{namespace}:cache:{key}}。
     *
     * <p>调用方传入的 {@code key} 本身是分层的，形如
     * {@code {entity}:{hash}}（精确缓存）或
     * {@code {entity}:sem:{fpHash}:{hardKey}:{semHash}}（语义缓存）。
     * 带上 entity 段是为了支持"按实体整体失效"：SCAN
     * {@code iris:{ns}:cache:{entity}:*} 即可命中该实体下精确缓存 + 语义缓存全部条目。
     */
    @Override
    public String cacheKey(String namespace, String key) {
        return RedisKeyPatterns.CACHE.formatted(namespace, key);
    }

    /** 实体缓存 key 索引：{@code iris:{ns}:cacheidx:{entity}}（SET，成员为缓存条目相对 key 段）。 */
    @Override
    public String cacheIndexKey(String namespace, String entity) {
        return RedisKeyPatterns.CACHE_INDEX.formatted(namespace, entity);
    }

    /** Query Engine 索引名：{@code iris:{namespace}:index:{entity}}（T1 引入）。 */
    @Override
    public String indexKey(String namespace, String entity) {
        return RedisKeyPatterns.INDEX.formatted(namespace, entity);
    }

    /** 动态 agent key 注册表：{@code iris:security:agent-keys}（全局无 namespace）。 */
    @Override
    public String securityAgentKeysKey() {
        return RedisKeyPatterns.SECURITY_AGENT_KEYS;
    }
}

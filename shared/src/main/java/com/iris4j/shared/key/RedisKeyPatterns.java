package com.iris4j.shared.key;

/**
 * Redis 逻辑 key 模板。
 *
 * <p><b>硬性约定</b>：所有实际 key 都必须经由 {@link KeyStrategy} 基于这里的模板生成，
 * 禁止各模块自行拼接 key 字符串。本类是常量的唯一出处。
 *
 * <p><b>全局前缀 {@code iris}</b>：与同库可能共存的其他业务数据隔开，
 * 也方便 {@code redis-cli --scan --pattern 'iris:*'} 一次性盘点本系统数据。
 *
 * <p><b>冒号分层</b>：Redis 生态（redis-cli / RedisInsight / 监控工具）默认按冒号
 * 做树形分组展示，命名遵循这个惯例能直接得到可读的层级视图。
 */
public final class RedisKeyPatterns {

    /** 工具类禁止实例化。 */
    private RedisKeyPatterns() {
    }

    /** 全局前缀。 */
    public static final String PREFIX = "iris";

    /**
     * 实体投影：{@code iris:{namespace}:entity:{entity}:{primaryKey}}
     *
     * <p>存放 RedisJSON 文档（一行实体的字段映射）。等值过滤查询时把 primaryKey 段
     * 替换为 {@code *} 得到 SCAN 模式串。
     */
    public static final String ENTITY = PREFIX + ":%s:entity:%s:%s";

    /**
     * CDC 数据流：{@code iris:{namespace}:stream:{source}}
     *
     * <p>iris4j 自管流的命名。实际 Debezium sink 写入的 stream 名取自配置
     * {@code iris.cdc.sources[].stream}（= topic 名），不走本模板。
     */
    public static final String STREAM = PREFIX + ":%s:stream:%s";

    /** 实体 Schema：{@code iris:{namespace}:schema:{entity}}。当前 Schema 存 YAML，为 Redis 化预留。 */
    public static final String SCHEMA = PREFIX + ":%s:schema:%s";

    /** 源消费位点元信息：{@code iris:{namespace}:meta:source-position}。为自建位点管理预留。 */
    public static final String SOURCE_POSITION = PREFIX + ":%s:meta:source-position";

    /** 工作记忆：{@code iris:{namespace}:memory:working:{sessionId}}（值是一个 JSON 数组）。 */
    public static final String WORKING_MEMORY = PREFIX + ":%s:memory:working:%s";

    /** 长期记忆：{@code iris:{namespace}:memory:long:{memoryId}}（值是单条记忆的 JSON 对象）。 */
    public static final String LONG_TERM_MEMORY = PREFIX + ":%s:memory:long:%s";

    /**
     * 长期记忆条目索引：{@code iris:{ns}:memidx:long}，SET。
     *
     * <p><b>解决什么问题</b>：记忆关键词检索（{@code searchByKeyword}）原实现
     * {@code SCAN MATCH iris:{ns}:memory:long:*} 全 keyspace 遍历，是请求路径上
     * 最后一个 O(N) 残留。本索引把「枚举记忆文档」降为
     * {@code SMEMBERS}（成员数 = 记忆条数，与全库规模彻底解耦）。
     *
     * <p>成员 = memoryId（相对段，与 {@link #LONG_TERM_MEMORY} 第三段同构），
     * 便于直接拼回文档 key 批量回捞。<b>索引不设 TTL</b>——记忆本体永不过期，
     * 成员指向已删除文档时无害（回捞 null 即惰性 SREM 清理）。
     *
     * <p><b>存量回填</b>：首次检索时一次性 SCAN 回填（标记 key 见
     * {@link #LONG_TERM_MEMORY_INDEX_BACKFILL}），此后写入/删除随写随维护。
     */
    public static final String LONG_TERM_MEMORY_INDEX = PREFIX + ":%s:memidx:long";

    /**
     * 长期记忆索引回填标记：{@code iris:{ns}:memidx:long:backfilled}，string "1"。
     *
     * <p>SET NX 占位：保证每个 namespace 的冷启动 SCAN 只发生一次；
     * 占位与回填之间的并发写入无害（save 无条件 SADD，幂等）。
     */
    public static final String LONG_TERM_MEMORY_INDEX_BACKFILL =
            LONG_TERM_MEMORY_INDEX + ":backfilled";

    /**
     * 长期记忆向量：{@code iris:{namespace}:memory:vec:{fpHash}:{memoryId}}（T4 引入，HASH）。
     *
     * <p>field {@code vec} = float32 小端序二进制，供 HNSW 向量索引消费。
     * <b>fpHash 段是向量空间指纹哈希</b>（与语义缓存的双索引切换同机制）：
     * 换 Embedder 模型/维度后新向量进新前缀、建新索引，新旧向量空间物理隔离，
     * 不同维度的向量绝不会被误索引到同一个 HNSW 图里。
     */
    public static final String MEMORY_VECTOR = PREFIX + ":%s:memory:vec:%s:%s";

    /**
     * 工作记忆向量：{@code iris:{namespace}:memory:wm:{fpHash}:{sessionId}:{entryId}}
     * （工作记忆语义索引，HASH）。
     *
     * <p>field {@code vec} = float32 向量、field {@code session} = 会话 ID（TAG 过滤段），
     * TTL 与所属会话对齐——会话消失向量自动过期，不做主动清理的强依赖。
     */
    public static final String WORKING_MEMORY_VECTOR = PREFIX + ":%s:memory:wm:%s:%s:%s";

    /** 工作记忆向量前缀：{@code iris:{namespace}:memory:wm:{fpHash}:}（FT.CREATE 的 PREFIX 段与 SCAN 清理用）。 */
    public static final String WORKING_MEMORY_VECTOR_PREFIX = PREFIX + ":%s:memory:wm:%s:";

    /**
     * 工作记忆操作互斥锁：{@code iris:{namespace}:memory:wlock:{sessionId}}。
     *
     * <p>渐进摘要写回与并发追加交错时用（SET NX PX 短锁）；
     * 常规追加走 Lua 原子脚本，不依赖此锁。
     */
    public static final String WORKING_MEMORY_LOCK = PREFIX + ":%s:memory:wlock:%s";

    /** 精确缓存：{@code iris:{namespace}:cache:{key}}（值是带 TTL 的 JSON 字符串）。 */
    public static final String CACHE = PREFIX + ":%s:cache:%s";

    /**
     * 实体缓存 key 索引：{@code iris:{namespace}:cacheidx:{entity}}，SET 结构。
     *
     * <p><b>解决什么问题</b>：CDC 每次投影后要失效该实体的全部查询缓存，原实现用
     * {@code SCAN MATCH iris:{ns}:cache:{entity}:*}——Redis 的 SCAN 是<b>整库游标遍历</b>，
     * MATCH 只做服务端过滤、不减少遍历量，因此代价是 O(全 keyspace 键数) 而非 O(命中数)。
     * 标定数据：16 万键下单次约 2.8 秒，足以拖垮并发消费者（吞吐掉到 0）。
     *
     * <p><b>本索引怎么解</b>：写入缓存条目时把它的相对 key 段登记进本集合，
     * 失效时 {@code SMEMBERS} 取出成员逐个 DEL——成员数 = 该实体缓存条目数
     * （通常 &lt;100），与全库规模彻底解耦。
     *
     * <p><b>为什么可以信任"集合为空 ⟹ 无缓存条目"</b>：索引 TTL = 条目 TTL + 60s 缓冲，
     * 且每次写入都刷新，故任一条目存活期内索引必然存活（条目 TTL 起算点不晚于索引，
     * 索引还多 60s）。索引与条目同在 Redis、同受 RDB/AOF 保护，不存在只丢其一的路径。
     * 手动 DEL 或容量淘汰造成的「集合成员指向已消失的 key」无害——DEL 返回 0 即跳过。
     *
     * <p>成员存的是<b>相对 key 段</b>（{@code {entity}:{hash}} 或
     * {@code {entity}:sem:...}），与 {@link KeyStrategy#cacheKey} 的 key 参数同构，
     * 便于直接拼回完整 key 删除。
     */
    public static final String CACHE_INDEX = PREFIX + ":%s:cacheidx:%s";

    /**
     * Query Engine 二级索引：{@code iris:{namespace}:index:{entity}}（T1 引入）。
     *
     * <p>索引名只作逻辑归类展示，真正的索引内容挂在 PREFIX 段
     * {@code iris:{namespace}:entity:{entity}:} 上（FT.CREATE 的 PREFIX 参数）。
     * 与实体 key 同层，一眼能看出"这个索引覆盖哪个实体的哪些文档"。
     */
    public static final String INDEX = PREFIX + ":%s:index:%s";

    /**
     * 动态 agent key 注册表（全局唯一、不带 namespace）：
     * {@code iris:security:agent-keys}。
     *
     * <p>HASH 结构：field = agent key 的 SHA-256 十六进制指纹，
     * value = {@code {"agentId":..,"tags":[..],"updatedAt":..}}。
     * <b>Redis 里不存明文 key</b>——指纹单向，泄漏投影存储也不泄漏凭证本体；
     * 明文 key 只存在于调用方与 yml（与现状同等信任级别）。
     */
    public static final String SECURITY_AGENT_KEYS = PREFIX + ":security:agent-keys";

    /**
     * LLM 响应语义缓存文档：{@code iris:{ns}:llmcache:{modelTag}:{promptHash}}。
     *
     * <p>value = 单条缓存的 JSON（prompt/response/model/createdAt）。
     * key 天然去重：同 model 同 prompt 覆盖写；不同 model 的 modelTag 不同互不覆盖。
     * modelTag = SHA-256(model) 前 8 位（model 为空用 "none"）——model 名可能含
     * 保留字符，统一哈希化保证 key 分段安全。
     */
    public static final String LLM_CACHE = PREFIX + ":%s:llmcache:%s:%s";

    /**
     * 实体数据版本计数器（数据版本守卫）：{@code iris:{ns}:ver:{entity}}。
     *
     * <p>CDC 消费点每次成功投影该实体的变更即 INCR；LLM 缓存条目写入时记录
     * 依赖实体当时的版本，查找命中后校验版本一致才通过——数据变了旧答案自动失效。
     */
    public static final String ENTITY_VERSION = PREFIX + ":%s:ver:%s";

    /**
     * LLM 响应缓存向量：{@code iris:{ns}:llmcache:vec:{fpHash}:{modelTag}:{promptHash}}。
     *
     * <p>field {@code vec} = prompt 的 float32 向量、field {@code model} = modelTag
     * （TAG 过滤段）。相似 prompt 的 KNN 命中后按后两段重组出文档 key 回捞。
     */
    public static final String LLM_CACHE_VECTOR = PREFIX + ":%s:llmcache:vec:%s:%s:%s";

    /** LLM 响应缓存向量前缀（FT.CREATE 的 PREFIX 段用，含尾部冒号）。 */
    public static final String LLM_CACHE_VECTOR_PREFIX = PREFIX + ":%s:llmcache:vec:%s:";

    /** LLM 响应缓存向量 SCAN 模式（跨指纹，不带尾部冒号）：{@code iris:{namespace}:llmcache:vec:*}。 */
    public static final String LLM_CACHE_VECTOR_SCAN = PREFIX + ":%s:llmcache:vec:*";

    /**
     * LLM 响应缓存<b>条目索引</b>：{@code iris:{ns}:llmcache:idx}，ZSET。
     *
     * <p>member = 文档 key、score = 条目过期的 epoch 毫秒。
     * 用途是把"统计条目数 / 淘汰最旧条目"从全 keyspace {@code SCAN} 降为
     * {@code ZCARD}（O(1)）与 {@code ZRANGE}（O(logN)）——原实现用
     * {@code SCAN MATCH iris:{ns}:llmcache:*:*}，代价与 keyspace 总量成正比
     * （标定数据：366 万键下约 7 秒，且命中仅 2 条），且挂在每次回答的请求路径上。
     */
    public static final String LLM_CACHE_INDEX = PREFIX + ":%s:llmcache:idx";

    /**
     * LLM 响应缓存<b>向量索引</b>：{@code iris:{ns}:llmcache:vecidx}，ZSET。
     *
     * <p>member = 向量 key（含 fpHash 段）、score = 过期 epoch 毫秒。
     * 向量 key 是四段式（{@code vec:{fpHash}:{modelTag}:{promptHash}}），
     * 淘汰某条目时要连带删掉它<b>各指纹版本</b>的向量，原实现对此再跑一次
     * 全库 SCAN；有了本索引只需在索引（成员数 ≤ max-entries）内按后缀匹配。
     */
    public static final String LLM_CACHE_VECTOR_INDEX = PREFIX + ":%s:llmcache:vecidx";

    /**
     * 配方草稿审核队列（配方自进化）：{@code iris:{ns}:recipe-drafts}，LIST。
     *
     * <p>member = 草稿 JSON（question/toolTrace/rounds/failed 等），LPUSH+LTRIM 封顶，
     * 人工经 REST 审核视图合入 graph.yml。
     */
    public static final String RECIPE_DRAFT = PREFIX + ":%s:recipe-drafts";
}

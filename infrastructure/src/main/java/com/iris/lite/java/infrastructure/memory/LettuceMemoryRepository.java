package com.iris.lite.java.infrastructure.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.lite.java.shared.util.LexTerms;
import com.iris.lite.java.shared.util.RrfFusion;
import com.iris.lite.java.shared.util.Vectors;
import com.iris.lite.java.application.rag.MultiQueryExpander;
import com.iris.lite.java.application.rerank.CrossEncoderReranker;
import com.iris.lite.java.cache.embedder.Embedder;
import com.iris.lite.java.infrastructure.rag.LexicalIndexManager;
import com.iris.lite.java.infrastructure.redis.RedisAdapter;
import com.iris.lite.java.infrastructure.redis.SearchQueryTranslator;
import com.iris.lite.java.memory.LongTermMemory;
import com.iris.lite.java.memory.MemoryRepository;
import com.iris.lite.java.memory.SearchMode;
import com.iris.lite.java.memory.WorkingMemoryDocument;
import com.iris.lite.java.memory.WorkingMemoryEntry;
import com.iris.lite.java.shared.key.KeyStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 基于 RedisJSON 的记忆仓储实现。
 *
 * <p><b>工作记忆</b>：一个 (namespace, sessionId) 对应一个 key，值为
 * {@link WorkingMemoryDocument} JSON 对象（{@code context, strategy, owner, entries[]}）。
 * append 走 <b>Lua 原子脚本</b>：服务端一次完成"旧数组格式迁移 + 条目追加"，
 * 并发追加不丢写（读-改-写方式在并发下会丢条目）。
 *
 * <p><b>会话渐进摘要</b>：摘要服务通过 {@link #replaceWorkingMemory} 写回
 * 滚动摘要与新条目集；与 append 的交错由 {@code SET NX PX} 短锁互斥。
 *
 * <p><b>长期记忆</b>：本体一条一个 RedisJSON key；伴生写 float32 向量到
 * {@code iris:{ns}:memory:vec:{fpHash}:{id}}（HASH），归入按 Embedder 指纹
 * 隔离的 HNSW 索引。检索三模式：KNN 语义 / 关键词包含 / 混合（默认）。
 *
 * <p><b>工作记忆语义索引</b>：条目向量写入
 * {@code iris:{ns}:memory:wm:{fpHash}:{sessionId}:{entryId}}（HASH，含 session
 * TAG 字段），归入独立索引；检索 {@code @session:{sid}=>[KNN ...]} 会话内命中。
 * 向量 TTL 与会话对齐——会话过期向量自动消失，clear 时主动清扫。
 */
@Component
public class LettuceMemoryRepository implements MemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(LettuceMemoryRepository.class);

    /** 批量 JSON.GET 回捞的批大小（与实体投影 fetch-batch 同量级，控制单次 pipeline 体积）。 */
    private static final int DOC_FETCH_BATCH = 500;

    /**
     * 工作记忆原子追加脚本：
     * <ol>
     *   <li>key 不存在 → 直接构造新文档（含首条 entry 与 owner，ownerId 层）；</li>
     *   <li>值以 '[' 开头 → 旧版纯数组格式，先整体迁移为文档格式（补 owner）；</li>
     *   <li>文档缺 owner 字段（ownerId 层之前的旧文档）→ 补写本条目的 owner；</li>
     *   <li>JSON.ARRAPPEND 追加新条目到 $.entries。</li>
     * </ol>
     * 全程在 Redis 服务端执行，天然原子——并发追加同会话不再丢写。
     * ARGV[2] 为 Java 侧 Jackson 转义后的带引号 owner 字符串，直接内嵌 JSON，
     * 无需在 Lua 里手工转义。
     */
    private static final String APPEND_SCRIPT = """
            local v = redis.call('JSON.GET', KEYS[1])
            if (not v) or v == '' then
              redis.call('JSON.SET', KEYS[1], '$',
                '{"context":"","strategy":"","owner":' .. ARGV[2] .. ',"entries":[' .. ARGV[1] .. ']}')
              return 1
            end
            if string.sub(v, 1, 1) == '[' then
              v = '{"context":"","strategy":"","owner":' .. ARGV[2] .. ',"entries":' .. v .. '}'
              redis.call('JSON.SET', KEYS[1], '$', v)
            end
            local owner = redis.call('JSON.GET', KEYS[1], '$.owner')
            if (not owner) or owner == '' then
              redis.call('JSON.SET', KEYS[1], '$.owner', ARGV[2])
            end
            redis.call('JSON.ARRAPPEND', KEYS[1], '$.entries', ARGV[1])
            return 2
            """;

    private final RedisAdapter redis;
    private final KeyStrategy keys;
    private final ObjectMapper objectMapper;

    /** 可选 Embedder：有则启用语义检索与向量伴生写，无则整体退回关键词路径。 */
    private final ObjectProvider<Embedder> embedderProvider;

    /** 记忆向量索引的惰性确保/定位（内部已按指纹缓存，重复调用零开销）。 */
    private final MemoryVectorIndexManager vectorIndex;

    /**
     * 工作记忆会话 TTL（秒）。<=0 表示永不过期（仅本地调试建议）。
     * 工作记忆即托管会话存储——靠 TTL 自动清理
     * 不再活跃的会话，否则会话状态随时间无限堆积。
     */
    private final long workingTtlSeconds;

    /** 工作记忆语义索引开关（无 Embedder 时同样旁路）。 */
    private final boolean wmIndexEnabled;

    // ---------- RAG 多路召回：稠密 KNN + BM25 词法 + LLM 多查询 → RRF → 重排 ----------

    private final LexicalIndexManager lexicalIndex;
    private final ObjectProvider<CrossEncoderReranker> rerankerProvider;
    private final MultiQueryExpander multiQueryExpander;
    private final boolean ragLexicalEnabled;
    private final int rrfK;
    private final int ragCandidates;

    public LettuceMemoryRepository(
            RedisAdapter redis,
            KeyStrategy keys,
            ObjectMapper objectMapper,
            ObjectProvider<Embedder> embedderProvider,
            MemoryVectorIndexManager vectorIndex,
            LexicalIndexManager lexicalIndex,
            ObjectProvider<CrossEncoderReranker> rerankerProvider,
            MultiQueryExpander multiQueryExpander,
            @Value("${iris.memory.working-ttl-seconds:86400}") long workingTtlSeconds,
            @Value("${iris.memory.wm-index.enabled:true}") boolean wmIndexEnabled,
            @Value("${iris.rag.lexical.enabled:true}") boolean ragLexicalEnabled,
            @Value("${iris.rag.rrf-k:60}") int rrfK,
            @Value("${iris.rag.candidates:12}") int ragCandidates) {
        this.redis = redis;
        this.keys = keys;
        this.objectMapper = objectMapper;
        this.embedderProvider = embedderProvider;
        this.vectorIndex = vectorIndex;
        this.lexicalIndex = lexicalIndex;
        this.rerankerProvider = rerankerProvider;
        this.multiQueryExpander = multiQueryExpander;
        this.workingTtlSeconds = workingTtlSeconds;
        this.wmIndexEnabled = wmIndexEnabled;
        this.ragLexicalEnabled = ragLexicalEnabled;
        this.rrfK = rrfK;
        this.ragCandidates = Math.max(1, ragCandidates);
    }

    /**
     * 追加一条工作记忆（Lua 原子脚本，并发安全）。
     *
     * <p>追加后（有 Embedder 且索引开启）伴生写条目向量——嵌入在请求线程
     * 完成（bge 约 2.6ms/条，与实体投影写侧同级别的可接受开销）。
     */
    @Override
    public void appendWorkingMemory(String namespace, String sessionId, WorkingMemoryEntry entry) {
        String key = keys.workingMemoryKey(namespace, sessionId);
        try {
            // ARGV[2] = Jackson 转义后的带引号 owner（Lua 侧直接内嵌 JSON，防注入）
            redis.eval(APPEND_SCRIPT, List.of(key), writeJson(entry), writeJson(entry.owner()));
        } catch (Exception e) {
            throw new IllegalStateException("工作记忆原子追加失败: " + e.getMessage(), e);
        }
        // 每次追加刷新 TTL——"活跃会话自动续期"，最后一次写入起算
        refreshWorkingTtl(namespace, sessionId, key);
        // 工作记忆语义索引伴生写（失败不影响记忆本体——向量只是检索加速结构）
        writeWorkingVector(namespace, sessionId, entry);
        log.debug("工作记忆已追加 ns={} session={} entry={}", namespace, sessionId, entry.id());
    }

    /** 读取某会话的完整工作记忆文档（旧数组格式读时迁移视图）；无记录返回空文档。 */
    @Override
    public WorkingMemoryDocument getWorkingMemoryDocument(String namespace, String sessionId) {
        String key = keys.workingMemoryKey(namespace, sessionId);
        String json = redis.jsonGet(key);
        if (json == null) {
            return WorkingMemoryDocument.empty();
        }
        WorkingMemoryDocument document = readWorkingDocument(json);
        // 读取也续期：Agent 在会话尾声做"回忆整理"时不应读到过期的中间态
        if (!document.entries().isEmpty()) {
            refreshWorkingTtl(namespace, sessionId, key);
        }
        log.debug("工作记忆读取 ns={} session={} 条数={} contextChars={}",
                namespace, sessionId, document.entries().size(), document.context().length());
        return document;
    }

    /**
     * 整体替换会话工作记忆文档（渐进摘要写回用）。
     *
     * <p><b>并发约定</b>：调用方必须先持有会话短锁
     * （{@link #tryWorkingMemoryLock}），否则可能与 append 交错丢条目。
     */
    @Override
    public void replaceWorkingMemory(String namespace, String sessionId, WorkingMemoryDocument document) {
        String key = keys.workingMemoryKey(namespace, sessionId);
        redis.jsonSet(key, writeJson(document));
        refreshWorkingTtl(namespace, sessionId, key);
        log.debug("工作记忆文档已替换 ns={} session={} 条数={} contextChars={}",
                namespace, sessionId, document.entries().size(), document.context().length());
    }

    /** 设置会话级抽取策略；读-改-写全程持短锁，避免覆盖并发追加的条目。 */
    @Override
    public void saveWorkingMemoryStrategy(String namespace, String sessionId, String strategy) {
        String lockKey = keys.workingMemoryLockKey(namespace, sessionId);
        if (!tryWorkingMemoryLock(namespace, sessionId)) {
            log.warn("会话策略设置未获锁，放弃本次设置（下次写入可重试）: session={}", sessionId);
            return;
        }
        try {
            WorkingMemoryDocument doc = getWorkingMemoryDocument(namespace, sessionId);
            // 保留文档级 owner——策略设置是读-改-写，不能把归属洗掉
            replaceWorkingMemory(namespace, sessionId,
                    new WorkingMemoryDocument(doc.context(), strategy == null ? "" : strategy,
                            doc.owner(), doc.entries()));
        } finally {
            redis.del(lockKey);
        }
    }

    /**
     * 清空某会话的工作记忆：DEL 会话文档 + 清扫该会话的全部条目向量。
     */
    @Override
    public void clearWorkingMemory(String namespace, String sessionId) {
        redis.del(keys.workingMemoryKey(namespace, sessionId));
        clearWorkingVectors(namespace, sessionId);
        log.debug("工作记忆已清空 ns={} session={}", namespace, sessionId);
    }

    /**
     * 尝试获取会话操作短锁（SET NX PX 5s + 有限重试）。
     * 摘要写回与策略设置这类"读-改-写"操作与高频 append 互斥用。
     */
    @Override
    public boolean tryWorkingMemoryLock(String namespace, String sessionId) {
        String lockKey = keys.workingMemoryLockKey(namespace, sessionId);
        for (int i = 0; i < 5; i++) {
            if (redis.setNxPx(lockKey, "1", 5000)) {
                return true;
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 释放会话操作短锁（配对方法；锁不存在时静默）。 */
    @Override
    public void releaseWorkingMemoryLock(String namespace, String sessionId) {
        redis.del(keys.workingMemoryLockKey(namespace, sessionId));
    }

    /**
     * 保存一条长期记忆，并（有 Embedder 时）伴生写入语义向量。
     *
     * <p><b>向量伴生写的失败边界</b>：向量写入失败<b>不回滚</b>记忆本体——
     * 记忆是权威数据，向量只是检索加速结构，丢了顶多这条记忆走关键词兜底；
     * 因向量失败丢记忆才是真正的数据损失。
     */
    @Override
    public LongTermMemory saveLongTermMemory(LongTermMemory memory) {
        String key = keys.longTermMemoryKey(memory.namespace(), memory.id());
        redis.jsonSet(key, writeJson(memory));
        // RAG 词法通道伴生写：lex 派生字段（词项化文本）供 BM25 FT 索引打分。
        // 失败只降级为"词法召回漏此条"，不回滚权威数据——与向量伴生写同一边界
        if (ragLexicalEnabled) {
            try {
                lexicalIndex.ensureMemoryIndex(memory.namespace());
                redis.jsonSetPath(key, "$.lex",
                        objectMapper.writeValueAsString(LexTerms.toLexText(memory.content())));
            } catch (Exception e) {
                log.debug("长期记忆 lex 伴生写失败（词法召回将漏此条）: id={} - {}",
                        memory.id(), e.getMessage());
            }
        }
        // 条目索引随写随维护（索引驱动）：幂等 SADD，失败不影响记忆本体
        try {
            redis.sadd(keys.longTermMemoryIndexKey(memory.namespace()), memory.id());
        } catch (Exception e) {
            log.warn("长期记忆索引登记失败（记忆本体已保存，检索可能漏此条）: id={} - {}",
                    memory.id(), e.getMessage());
        }
        log.debug("长期记忆已保存 ns={} id={} type={} memoryType={}",
                memory.namespace(), memory.id(), memory.type(), memory.memoryType());

        Embedder embedder = embedderProvider.getIfAvailable();
        if (embedder == null) {
            return memory;
        }
        try {
            vectorIndex.ensureIndex(memory.namespace());
            float[] vector = embedder.embed(memory.content());
            String vecKey = keys.memoryVectorKey(
                    memory.namespace(), embedder.fingerprintHash(), memory.id());
            redis.hsetBinary(vecKey, "vec", Vectors.toFloat32Bytes(vector));
            // owner TAG（ownerId 层）：KNN 检索时 @owner:{} 精确预过滤的依据；
            // 缺失该字段的老向量在带 owner 检索中漏检——ensureIndex 创建新索引时回填
            redis.hset(vecKey, "owner", memory.owner());
            log.debug("长期记忆向量已写入 ns={} id={} dim={}",
                    memory.namespace(), memory.id(), vector.length);
        } catch (Exception e) {
            // 不让检索结构的问题拖垮权威数据写入
            log.warn("长期记忆向量写入失败（记忆本体已保存，检索将走关键词兜底）: id={} - {}",
                    memory.id(), e.getMessage(), e);
        }
        return memory;
    }

    /**
     * 删除一条长期记忆：DEL 本体 + 清扫所有指纹空间下该 id 的向量。
     *
     * <p>向量清理用尾部匹配 SCAN（{@code ...:vec:*:{id}}）——换过 Embedder 的
     * namespace 可能存在多代指纹向量，一并清掉才算删干净。
     */
    @Override
    public void deleteLongTermMemory(String namespace, String memoryId) {
        redis.del(keys.longTermMemoryKey(namespace, memoryId));
        // 索引成员同步摘除（去 SCAN 改造）：残留成员本身无害（回捞 null 惰性清理），
        // 主动摘除只为保持 SMEMBERS 规模与真实记忆数一致
        try {
            redis.srem(keys.longTermMemoryIndexKey(namespace), memoryId);
        } catch (Exception e) {
            log.warn("长期记忆索引摘除失败（残留成员由检索惰性清理）: id={} - {}",
                    memoryId, e.getMessage());
        }
        String pattern = keys.memoryVectorKey(namespace, "*", memoryId);
        int cleaned = 0;
        for (String vecKey : redis.scanKeys(pattern)) {
            redis.del(vecKey);
            cleaned++;
        }
        log.debug("长期记忆已删除 ns={} id={} 向量清理={} 条", namespace, memoryId, cleaned);
    }

    /**
     * 检索长期记忆（三模式 + owner 过滤）。
     *
     * <p><b>SEMANTIC</b>：纯 KNN 单通道（契约不变，改写/同义命中）；
     * <b>KEYWORD</b>：词法 BM25 检索（引擎排序下推），词法不可用/单字查询落回
     * 旧的包含匹配路径；<b>HYBRID</b>（缺省）= RAG 多路召回：稠密 KNN + BM25 词法
     * （+ LLM 多查询改写触发时逐变体稠密）→ RRF 融合 → rerank（可用时）。
     *
     * <p>query 为空一律走关键词路径的"最近记忆"语义；全通道失败自动降级
     * 关键词兜底——检索增强挂了不能变成检索不可用。
     *
     * <p><b>owner 过滤（ownerId 层）</b>：owner != null 时稠密走
     * {@code @owner:{} TAG 精确预过滤}、词法走索引层 owner TAG 过滤（且回捞后
     * Java 层等值复滤双保险）——绝不做语义模糊（owner 隔离红线）。
     */
    @Override
    public List<LongTermMemory> searchLongTermMemory(
            String namespace, String owner, String query, int limit, SearchMode mode) {
        SearchMode effectiveMode = mode == null ? SearchMode.HYBRID : mode;
        if (query == null || query.isBlank()) {
            // query 为空 = "最近记忆"语义，与相似度无关，直接走关键词路径
            return searchByKeyword(namespace, owner, "", limit);
        }
        Embedder embedder = embedderProvider.getIfAvailable();
        if (effectiveMode == SearchMode.SEMANTIC) {
            try {
                if (embedder != null) {
                    List<LongTermMemory> hits = searchByKnn(namespace, owner, query, limit, embedder);
                    if (hits != null) {
                        log.debug("长期记忆语义检索 ns={} owner={} query='{}' 返回={}",
                                namespace, owner, query, hits.size());
                        return hits;
                    }
                }
            } catch (Exception e) {
                log.warn("长期记忆 KNN 检索失败，回退关键词路径: ns={} - {}", namespace, e.getMessage(), e);
            }
            return searchByKeyword(namespace, owner, query, limit);
        }
        if (effectiveMode == SearchMode.KEYWORD) {
            return searchKeywordMode(namespace, owner, query, limit);
        }
        return searchHybrid(namespace, owner, query, limit, embedder);
    }

    /** KEYWORD 模式：词法 BM25 优先（排序下推、O(索引)），不可用落回包含匹配。 */
    private List<LongTermMemory> searchKeywordMode(String namespace, String owner, String query, int limit) {
        if (ragLexicalEnabled) {
            List<String> ids = lexicalChannel(namespace, owner, query, limit);
            if (ids != null) {
                List<LongTermMemory> docs = fetchMemories(namespace, ids, owner);
                log.debug("长期记忆词法检索(BM25) ns={} query='{}' 召回={} 返回={}",
                        namespace, query, ids.size(), Math.min(docs.size(), limit));
                return docs.size() > limit ? new ArrayList<>(docs.subList(0, limit)) : docs;
            }
        }
        return searchByKeyword(namespace, owner, query, limit);
    }

    /**
     * HYBRID = RAG 多路召回主链路。
     *
     * <p>三通道：稠密 KNN（原查询）→ 词法 BM25（lex TEXT 索引）→ LLM 多查询改写
     * （{@code shouldExpand} 决定是否触发，逐变体稠密召回）。各通道产出按相关度
     * 降序的 memoryId 排名列表，RRF 融合出候选池；有重排器时 batch rerank 把
     * 排名升级为交叉编码器判别序（失败保持 RRF 序——记忆检索是增强路径，
     * 重排挂了不能不可用），最后截断 limit。
     */
    private List<LongTermMemory> searchHybrid(
            String namespace, String owner, String query, int limit, Embedder embedder) {
        long start = System.currentTimeMillis();
        List<List<String>> channels = new ArrayList<>();
        List<String> dense = denseChannel(namespace, owner, query, ragCandidates, embedder);
        if (dense != null && !dense.isEmpty()) {
            channels.add(dense);
        }
        List<String> lexical = ragLexicalEnabled
                ? lexicalChannel(namespace, owner, query, ragCandidates) : null;
        if (lexical != null && !lexical.isEmpty()) {
            channels.add(lexical);
        }
        boolean initialEmpty = channels.isEmpty();
        // LLM 多查询通道（on-miss：常规两通道有结果就不打扰 LLM；always：每次都改写）
        if (multiQueryExpander.shouldExpand(initialEmpty)) {
            for (String variant : multiQueryExpander.expand(query)) {
                List<String> variantDense = denseChannel(namespace, owner, variant, ragCandidates, embedder);
                if (variantDense != null && !variantDense.isEmpty()) {
                    channels.add(variantDense);
                }
            }
        }
        List<RrfFusion.Fused> fused = RrfFusion.fuse(rrfK, ragCandidates, channels);
        if (fused.isEmpty()) {
            // 三通道全空（服务故障/索引未就绪）：落回关键词兜底，检索不能不可用
            log.debug("RAG 融合池为空，落回关键词兜底 ns={} query='{}'", namespace, query);
            return searchByKeyword(namespace, owner, query, limit);
        }
        List<String> fusedIds = fused.stream().map(RrfFusion.Fused::id).toList();
        List<LongTermMemory> docs = fetchMemories(namespace, fusedIds, owner);
        log.debug("长期记忆混合检索 ns={} query='{}' dense={} lexical={} 融合={} 回捞={} 耗时={}ms",
                namespace, query,
                dense == null ? 0 : dense.size(), lexical == null ? 0 : lexical.size(),
                fused.size(), docs.size(), System.currentTimeMillis() - start);
        CrossEncoderReranker reranker = rerankerProvider.getIfAvailable();
        if (reranker != null && docs.size() > 1) {
            try {
                double[] scores = reranker.scoreBatch(query,
                        docs.stream().map(LongTermMemory::content).toList());
                Integer[] order = new Integer[docs.size()];
                for (int i = 0; i < order.length; i++) {
                    order[i] = i;
                }
                java.util.Arrays.sort(order,
                        Comparator.comparingDouble((Integer i) -> scores[i]).reversed());
                List<LongTermMemory> ranked = new ArrayList<>(docs.size());
                for (int i : order) {
                    ranked.add(docs.get(i));
                }
                docs = ranked;
            } catch (Exception e) {
                log.warn("记忆重排失败，保持 RRF 序: ns={} - {}", namespace, e.getMessage());
            }
        }
        return docs.size() > limit ? new ArrayList<>(docs.subList(0, limit)) : docs;
    }

    /**
     * 稠密通道：embed → KNN（owner TAG 预过滤）→ vecKey 剥前缀得 memoryId 排名列表。
     * 返回 null = 通道不可用（无 Embedder/空向量/链路故障），融合时视为空通道。
     */
    private List<String> denseChannel(
            String namespace, String owner, String query, int limit, Embedder embedder) {
        if (embedder == null) {
            return null;
        }
        try {
            vectorIndex.ensureIndex(namespace);
            float[] queryVec = embedder.embed(query);
            float normSq = 0;
            for (float x : queryVec) {
                normSq += x * x;
            }
            if (normSq == 0) {
                return null;
            }
            String prefix = vectorIndex.vectorPrefix(namespace);
            String filter = owner == null
                    ? "*"
                    : "@owner:{" + SearchQueryTranslator.quoteTag(owner) + "}";
            List<String> vecKeys = redis.ftSearchKnn(
                    vectorIndex.indexName(namespace),
                    filter + "=>[KNN " + limit + " @vec $q]",
                    Vectors.toFloat32Bytes(queryVec),
                    limit);
            return vecKeys.stream().map(k -> k.substring(prefix.length())).toList();
        } catch (Exception e) {
            log.debug("稠密通道失败（视为空通道）: ns={} - {}", namespace, e.getMessage());
            return null;
        }
    }

    /**
     * 词法通道：查询词项化 → BM25 FT 检索（owner TAG 预过滤）→ 文档 key 剥前缀得
     * memoryId 排名列表。返回 null = 通道不可用（无可用词项/索引未就绪/故障）。
     *
     * <p>单个 CJK 字符的词项在 bigram 索引里永远无匹配（文档词项最小粒度是
     * bigram/整词），直接过滤——单字查询由调用方落回包含匹配路径。
     */
    private List<String> lexicalChannel(String namespace, String owner, String query, int limit) {
        try {
            List<String> usable = LexTerms.tokens(query).stream()
                    .filter(t -> t.length() > 1 || t.charAt(0) < 0x2E80)
                    .toList();
            if (usable.isEmpty()) {
                return null;
            }
            lexicalIndex.ensureMemoryIndex(namespace);
            String terms = String.join("|", usable.subList(0, Math.min(usable.size(), 24)));
            String q = (owner == null ? "" : "@owner:{" + SearchQueryTranslator.quoteTag(owner) + "} ")
                    + "@lex:(" + terms + ")";
            List<String> docKeys = redis.ftSearchRank(
                    lexicalIndex.memoryLexIndexName(namespace), q, limit);
            String prefix = keys.longTermMemoryKey(namespace, "");
            return docKeys.stream().map(k -> k.substring(prefix.length())).toList();
        } catch (Exception e) {
            log.debug("词法通道失败（视为空通道）: ns={} - {}", namespace, e.getMessage());
            return null;
        }
    }

    /**
     * 按记忆 id 批量回捞本体（融合候选 ≤ ragCandidates，一次 pipeline），保持传入
     * （融合排名）顺序；owner 等值过滤与悬空 id 跳过。批量失败回落逐条（单 key
     * 异常不拖垮整批，与关键词路径同标准）。
     */
    private List<LongTermMemory> fetchMemories(String namespace, List<String> ids, String owner) {
        List<String> docKeys = new ArrayList<>(ids.size());
        for (String id : ids) {
            docKeys.add(keys.longTermMemoryKey(namespace, id));
        }
        List<String> jsons;
        try {
            jsons = redis.jsonGetPathBatch(docKeys, "$");
        } catch (Exception e) {
            log.debug("混合检索批量回捞失败，回落逐条: {}", e.getMessage(), e);
            jsons = new ArrayList<>(docKeys.size());
            for (String docKey : docKeys) {
                try {
                    jsons.add(redis.jsonGet(docKey));
                } catch (Exception ignored) {
                    jsons.add(null);
                }
            }
        }
        List<LongTermMemory> result = new ArrayList<>(ids.size());
        for (int i = 0; i < jsons.size(); i++) {
            String json = jsons.get(i);
            if (json == null) {
                continue; // 悬空：条目已删除（词法索引随文档消失，稠密向量可能残留）
            }
            LongTermMemory memory = readLongTerm(RedisAdapter.unwrapJsonPathResult(json));
            // owner 等值复滤：词法通道已在索引层过滤，这里是双保险（owner 隔离红线）
            if (owner != null && !owner.equals(memory.owner())) {
                continue;
            }
            result.add(memory);
        }
        return result;
    }

    /**
     * 工作记忆语义检索：
     * 在指定会话内 KNN，返回语义最相关的现存条目。
     *
     * <p>向量存在但条目已被摘要裁剪/会话已清 → 回捞 miss，静默跳过
     * （向量 TTL 与会话对齐，残留向量最多活到会话过期）。
     */
    @Override
    public List<WorkingMemoryEntry> searchWorkingMemory(String namespace, String sessionId, String query, int limit) {
        Embedder embedder = embedderProvider.getIfAvailable();
        if (embedder == null || !wmIndexEnabled || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            vectorIndex.ensureWorkingIndex(namespace);
            float[] queryVec = embedder.embed(query);
            float normSq = 0;
            for (float x : queryVec) {
                normSq += x * x;
            }
            if (normSq == 0) {
                return List.of();
            }
            // TAG 预过滤 + KNN：@session:{sid}=>[KNN n @vec $q]——会话内近邻，
            // 不串其他会话（TAG 值复用实体查询的 quoteTag 转义规则防注入）
            String filter = "@session:{" + SearchQueryTranslator.quoteTag(sessionId) + "}";
            List<String> vecKeys = redis.ftSearchKnn(
                    vectorIndex.workingIndexName(namespace),
                    filter + "=>[KNN " + limit + " @vec $q]",
                    Vectors.toFloat32Bytes(queryVec),
                    limit);
            String prefix = vectorIndex.workingVectorPrefix(namespace) + sessionId + ":";
            // 会话文档整读一次复用：原先每个 KNN 命中都独立 JSON.GET 全文档 + 反序列化
            // + EXPIRE 续期（limit 典型 10 → 同一文档拉 10 次）
            WorkingMemoryDocument document = getWorkingMemoryDocument(namespace, sessionId);
            List<WorkingMemoryEntry> hits = new ArrayList<>();
            for (String vecKey : vecKeys) {
                String entryId = vecKey.substring(prefix.length());
                WorkingMemoryEntry entry = findEntryIn(document, entryId);
                if (entry != null) {
                    hits.add(entry);
                }
            }
            log.debug("工作记忆语义检索 ns={} session={} query='{}' 命中={}",
                    namespace, sessionId, query, hits.size());
            return hits;
        } catch (Exception e) {
            log.warn("工作记忆语义检索失败（不影响工作记忆读取）: ns={} session={} - {}",
                    namespace, sessionId, e.getMessage(), e);
            return List.of();
        }
    }

    /** 按 id 从已读取的会话文档中找条目（KNN 回捞用）；找不到返回 null。 */
    private static WorkingMemoryEntry findEntryIn(WorkingMemoryDocument document, String entryId) {
        for (WorkingMemoryEntry entry : document.entries()) {
            if (entry.id().equals(entryId)) {
                return entry;
            }
        }
        return null;
    }

    /** 条目向量伴生写：embed → HASH(vec + session TAG)，TTL 与会话对齐，失败仅 warn。 */
    private void writeWorkingVector(String namespace, String sessionId, WorkingMemoryEntry entry) {
        Embedder embedder = embedderProvider.getIfAvailable();
        if (embedder == null || !wmIndexEnabled) {
            return;
        }
        try {
            vectorIndex.ensureWorkingIndex(namespace);
            String vecKey = keys.workingMemoryVectorKey(
                    namespace, embedder.fingerprintHash(), sessionId, entry.id());
            redis.hsetBinary(vecKey, "vec", Vectors.toFloat32Bytes(embedder.embed(entry.content())));
            redis.hset(vecKey, "session", sessionId);
            // owner TAG：条目级归属随向量落库（当前工作记忆检索不用它——@session
            // 已按会话隔离；落 TAG 是为未来跨会话按 owner 检索预留，成本一行）
            redis.hset(vecKey, "owner", entry.owner());
            if (workingTtlSeconds > 0) {
                redis.expire(vecKey, workingTtlSeconds);
            }
        } catch (Exception e) {
            log.warn("工作记忆向量写入失败（记忆本体不受影响）: session={} - {}",
                    sessionId, e.getMessage(), e);
        }
    }

    /** 清扫指定会话的全部条目向量（clear 会话时调用；TTL 过期的残留自然消失）。 */
    private void clearWorkingVectors(String namespace, String sessionId) {
        Embedder embedder = embedderProvider.getIfAvailable();
        if (embedder == null || !wmIndexEnabled) {
            return;
        }
        try {
            String pattern = vectorIndex.workingVectorPrefix(namespace) + sessionId + ":*";
            int cleaned = 0;
            for (String vecKey : redis.scanKeys(pattern)) {
                redis.del(vecKey);
                cleaned++;
            }
            log.debug("工作记忆向量已清扫 ns={} session={} 条数={}", namespace, sessionId, cleaned);
        } catch (Exception e) {
            log.warn("工作记忆向量清扫失败（TTL 会兜底过期）: session={} - {}", sessionId, e.getMessage(), e);
        }
    }

    /**
     * KNN 语义检索；返回 null 表示条件不满足（空向量），调用方应走关键词兜底。
     *
     * <p>owner != null 时 TAG 预过滤 {@code @owner:{...}=>[KNN ...]}——与
     * 工作记忆的 @session、LLM 缓存的 @model 同一范式；quoteTag 防注入。
     */
    private List<LongTermMemory> searchByKnn(
            String namespace, String owner, String query, int limit, Embedder embedder) {
        vectorIndex.ensureIndex(namespace);
        float[] queryVec = embedder.embed(query);
        // 空文本/退化向量：语义上"没有查询意图"，不值得返回任意 top-k
        float normSq = 0;
        for (float x : queryVec) {
            normSq += x * x;
        }
        if (normSq == 0) {
            return null;
        }
        String prefix = vectorIndex.vectorPrefix(namespace);
        String filter = owner == null
                ? "*"
                : "@owner:{" + SearchQueryTranslator.quoteTag(owner) + "}";
        List<String> vecKeys = redis.ftSearchKnn(
                vectorIndex.indexName(namespace),
                filter + "=>[KNN " + limit + " @vec $q]",
                Vectors.toFloat32Bytes(queryVec),
                limit);
        List<LongTermMemory> result = new ArrayList<>();
        // KNN 命中的文档 key 一次 JSON.GET pipeline 回捞（逐条是 N 次往返）
        String[] docKeys = new String[vecKeys.size()];
        for (int i = 0; i < vecKeys.size(); i++) {
            // 向量 key 剥前缀得 memoryId（前缀含尾部冒号）
            docKeys[i] = keys.longTermMemoryKey(namespace, vecKeys.get(i).substring(prefix.length()));
        }
        List<String> jsons = redis.jsonGetPathBatch(List.of(docKeys), "$");
        for (String json : jsons) {
            if (json == null) {
                continue;
            }
            // JSON.GET 带 path 恒返回数组包裹（$ → [doc]），必须剥掉再解析
            // （redis-cli JSON.GET key $ 返回 "[{...}]"——
            // 不剥则 readValue 抛异常，KNN 路径整体静默降级关键词）
            result.add(readLongTerm(RedisAdapter.unwrapJsonPathResult(json)));
        }
        return result;
    }

    /**
     * 关键词检索长期记忆（内容包含匹配），按创建时间倒序，最多返回 limit 条。
     *
     * <p><b>索引驱动</b>：
     * {@code SCAN MATCH iris:{ns}:memory:long:*} 全 keyspace 遍历挂在
     * 请求路径上（Agent 记忆检索），代价与全库键数成正比——本项目红线。改为：
     * <ol>
     *   <li>冷启动一次性 SCAN 回填索引（SET NX 标记保证只发生一次）；</li>
     *   <li>请求路径 {@code SMEMBERS} 取 memoryId（O(条数)），批量 JSON.GET 回捞；</li>
     *   <li>成员指向已删除文档 → 回捞 null → 惰性 SREM 清理。</li>
     * </ol>
     *
     * <p><b>为什么仍要全量取回再排序截断</b>：必须"最新的 limit 条匹配项"，
     * 而不是"枚举到的前 limit 条"——SMEMBERS 无序，边截会漏掉时间更新
     * 但枚举靠后的记忆。记忆条数量级（单 ns 通常 &lt;1 万）下批量回捞成本可接受；
     * 真到十万级记忆时应改走 FT 索引的 SORTBY 下推（届时关键词模式可降级为
     * 仅 KNN+TFIDF，或为 content 建 TEXT 索引），不在本层硬扛。
     */
    private List<LongTermMemory> searchByKeyword(String namespace, String owner, String query, int limit) {
        List<String> ids = ensureLongTermIndex(namespace);
        String indexKey = keys.longTermMemoryIndexKey(namespace);
        List<LongTermMemory> matched = new ArrayList<>();
        List<String> stale = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += DOC_FETCH_BATCH) {
            List<String> batch = ids.subList(from, Math.min(from + DOC_FETCH_BATCH, ids.size()));
            List<String> docKeys = new ArrayList<>(batch.size());
            for (String id : batch) {
                docKeys.add(keys.longTermMemoryKey(namespace, id));
            }
            List<String> jsons;
            try {
                jsons = redis.jsonGetPathBatch(docKeys, "$");
            } catch (Exception e) {
                // 批量失败回落逐条（单 key 异常不该拖垮整批），与投影降级同标准
                log.debug("记忆索引批量回捞失败，回落逐条: {}", e.getMessage(), e);
                jsons = new ArrayList<>(batch.size());
                for (String docKey : docKeys) {
                    try {
                        jsons.add(redis.jsonGet(docKey));
                    } catch (Exception ignored) {
                        jsons.add(null);
                    }
                }
            }
            for (int i = 0; i < jsons.size(); i++) {
                String json = jsons.get(i);
                if (json == null) {
                    stale.add(batch.get(i));
                    continue;
                }
                LongTermMemory memory = readLongTerm(RedisAdapter.unwrapJsonPathResult(json));
                // owner 过滤（ownerId 层）：TAG 预过滤是索引层的等值匹配，
                // 关键词路径的 Java 层过滤必须语义一致——也用等值
                if (owner != null && !owner.equals(memory.owner())) {
                    continue;
                }
                // query 为空 = 不过滤，返回最近记忆
                if (query.isBlank() || memory.content().contains(query)) {
                    matched.add(memory);
                }
            }
        }
        if (!stale.isEmpty()) {
            // 悬空成员惰性清理：不影响当前结果，只为保持索引规模准确
            try {
                redis.srem(indexKey, stale.toArray(String[]::new));
            } catch (Exception e) {
                log.debug("记忆索引悬空成员清理失败（无害）: {}", e.getMessage(), e);
            }
        }
        // 倒序：最近写入的排前面
        matched.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        log.debug("长期记忆关键词检索 ns={} query='{}' 索引成员={} 悬空={} 命中={} 返回={}",
                namespace, query, ids.size(), stale.size(), matched.size(), Math.min(matched.size(), limit));
        return matched.size() > limit ? new ArrayList<>(matched.subList(0, limit)) : matched;
    }

    /**
     * 确保长期记忆条目索引可用，返回全部 memoryId。
     *
     * <p><b>冷启动回填</b>：标记 key SET NX 占位成功者负责一次性 SCAN 全量回填；
     * 未抢到占位的并发请求直接读当前索引（可能暂时不全，回填完成后自愈）——
     * 记忆检索是兜底增强路径，短暂漏检可容忍，加分布式锁反而把简单问题复杂化。
     * 回填与并发 save 无冲突：save 无条件 SADD，回填的重复 SADD 幂等。
     */
    private List<String> ensureLongTermIndex(String namespace) {
        String indexKey = keys.longTermMemoryIndexKey(namespace);
        String backfillKey = keys.longTermMemoryIndexBackfillKey(namespace);
        if (redis.setIfAbsent(backfillKey, "1")) {
            try {
                String prefix = keys.longTermMemoryKey(namespace, "");
                List<String> members = new ArrayList<>();
                for (String key : redis.scanKeys(keys.longTermMemoryKey(namespace, "*"))) {
                    members.add(key.substring(prefix.length()));
                }
                for (int from = 0; from < members.size(); from += DOC_FETCH_BATCH) {
                    List<String> batch = members.subList(from, Math.min(from + DOC_FETCH_BATCH, members.size()));
                    redis.sadd(indexKey, batch.toArray(String[]::new));
                }
                log.info("长期记忆索引冷启动回填完成 ns={} 条数={}", namespace, members.size());
            } catch (Exception e) {
                // 回填失败：清掉标记让下次请求重试（索引残留成员幂等）
                log.warn("长期记忆索引冷启动回填失败，下次请求重试: ns={} - {}", namespace, e.getMessage(), e);
                try {
                    redis.del(backfillKey);
                } catch (Exception ignored) {
                    // 清标记失败仅意味着回填延后到标记被手动清理时
                }
            }
        }
        return new ArrayList<>(redis.smembers(indexKey));
    }

    /**
     * 解析工作记忆 JSON——兼容两种形态：
     * 旧版纯数组 {@code [{content,createdAt}...]} 与新版文档
     * {@code {context, strategy, entries[]}}。数组形态迁移为空摘要文档视图。
     */
    private WorkingMemoryDocument readWorkingDocument(String json) {
        try {
            if (json.startsWith("[")) {
                List<WorkingMemoryEntry> entries = objectMapper.readValue(
                        json, new TypeReference<List<WorkingMemoryEntry>>() {
                        });
                return new WorkingMemoryDocument("", "", entries);
            }
            return objectMapper.readValue(json, WorkingMemoryDocument.class);
        } catch (Exception e) {
            throw new IllegalStateException("工作记忆 JSON 解析失败", e);
        }
    }

    private LongTermMemory readLongTerm(String json) {
        try {
            return objectMapper.readValue(json, LongTermMemory.class);
        } catch (Exception e) {
            throw new IllegalStateException("长期记忆 JSON 解析失败", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("记忆序列化失败", e);
        }
    }

    /**
     * 刷新工作记忆 TTL。失败只降级为"不过期"，不影响记忆读写主流程。
     */
    private void refreshWorkingTtl(String namespace, String sessionId, String key) {
        if (workingTtlSeconds <= 0) {
            return;
        }
        try {
            redis.expire(key, workingTtlSeconds);
        } catch (Exception e) {
            log.warn("工作记忆 TTL 刷新失败（记忆本身不受影响）: ns={} session={} - {}",
                    namespace, sessionId, e.getMessage());
        }
    }
}

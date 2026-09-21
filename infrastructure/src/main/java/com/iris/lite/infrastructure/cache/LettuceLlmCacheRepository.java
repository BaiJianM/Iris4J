package com.iris.lite.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.lite.shared.util.LexTerms;
import com.iris.lite.shared.util.Vectors;
import com.iris.lite.cache.LlmCacheEntry;
import com.iris.lite.cache.LlmCacheRepository;
import com.iris.lite.infrastructure.memory.MemoryVectorIndexManager;
import com.iris.lite.infrastructure.rag.LexicalIndexManager;
import com.iris.lite.infrastructure.redis.RedisAdapter;
import com.iris.lite.infrastructure.redis.SearchQueryTranslator;
import com.iris.lite.shared.key.KeyStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * LLM 响应语义缓存的 Lettuce 实现。
 *
 * <p><b>存储结构</b>：
 * <ul>
 *   <li>文档 {@code iris:{ns}:llmcache:{modelTag}:{promptHash}}：条目 JSON，
 *       TTL 与写入时的配置一致；</li>
 *   <li>向量 {@code iris:{ns}:llmcache:vec:{fpHash}:{modelTag}:{promptHash}}：
 *       HASH（vec + model TAG），归入 {@code iris:{ns}:index:llmcache-{fpHash}}
 *       的 HNSW 索引；TTL 与文档对齐。</li>
 *   <li>条目索引 {@code iris:{ns}:llmcache:idx}（ZSET）：member = 文档 key、
 *       score = 过期 epoch 毫秒，写入时登记；</li>
 *   <li>向量索引 {@code iris:{ns}:llmcache:vecidx}（ZSET）：member = 向量 key，
 *       score 同上。</li>
 * </ul>
 *
 * <p><b>为什么要有索引</b>：Redis 没有"按前缀 O(1) 计数"的原语，
 * {@code SCAN MATCH iris:{ns}:llmcache:*:*} 统计条目数与挑选淘汰对象的代价
 * 取决于 <b>keyspace 总量</b>（MATCH 只在服务端逐个过滤，
 * 命中几条都要走完全库）——366 万键下单次 <b>7.08 秒</b>，且它挂在
 * <b>每次回答的请求路径</b>上（写缓存后无条件计数），表现为"答案流式输出结束后
 * 卡好几秒才出 done/用量统计"。索引化后：计数 {@code ZCARD} O(1)、
 * 淘汰 {@code ZRANGE} O(logN)、向量清理在索引内匹配，全部去掉了 SCAN。
 *
 * <p><b>索引的失效边界</b>：ZSET 不支持逐成员 TTL，成员过期副本靠
 * 每次计数/淘汰前的 {@code ZREMRANGEBYSCORE -inf now} 惰性清理；
 * 索引本身<b>不设 TTL</b>——若给索引设 TTL 而条目还活着，索引一旦先行消失，
 * 条目就永远无法被淘汰/计数（比留一个空集合危险得多）。
 *
 * <p><b>向量与文档的一致性策略</b>：向量是文档的"检索加速副本"，允许短暂不一致——
 * KNN 命中向量但文档已过期 → 回捞 miss 静默跳过（与工作记忆向量同策略）；
 * 文档在向量缺失时仍可精确命中（Embedder 缺席场景只写文档）。
 */
@Component
public class LettuceLlmCacheRepository implements LlmCacheRepository {

    private static final Logger log = LoggerFactory.getLogger(LettuceLlmCacheRepository.class);

    private final RedisAdapter redis;
    private final KeyStrategy keys;
    private final ObjectMapper objectMapper;
    private final MemoryVectorIndexManager vectorIndex;
    private final LexicalIndexManager lexicalIndex;
    /** 词法（BM25）通道开关（RAG）：关闭时 save 不伴生写 lex、检索跳过词法通道。 */
    private final boolean lexicalEnabled;

    public LettuceLlmCacheRepository(RedisAdapter redis, KeyStrategy keys,
                                     ObjectMapper objectMapper, MemoryVectorIndexManager vectorIndex,
                                     LexicalIndexManager lexicalIndex,
                                     @org.springframework.beans.factory.annotation.Value(
                                             "${iris.rag.lexical.enabled:true}") boolean lexicalEnabled) {
        this.redis = redis;
        this.keys = keys;
        this.objectMapper = objectMapper;
        this.vectorIndex = vectorIndex;
        this.lexicalIndex = lexicalIndex;
        this.lexicalEnabled = lexicalEnabled;
    }

    @Override
    public void save(String namespace, LlmCacheEntry entry, float[] vector, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds 必须为正数");
        }
        String docKey = keys.llmCacheKey(namespace, entry.modelTag(), entry.promptHash());
        String json = encode(entry);
        // 绝对过期时刻：既给索引当分数用（ZSET 按"谁先过期"排序），又与 SETEX 的
        // 相对 TTL 表达同一件事——同一 key 重复写入时 SETEX 与 ZADD 同步刷新。
        long expireAt = System.currentTimeMillis() + ttlSeconds * 1000L;
        // SETEX 原子写（值 + TTL 一步到位），不存在"写了没过期时间"的中间窗口
        redis.setex(docKey, ttlSeconds, json);
        redis.zadd(keys.llmCacheIndexKey(namespace), expireAt, docKey);
        // RAG 词法通道伴生写：lex 派生字段随文档落库（BM25 FT 索引的打分依据）。
        // 失败只降级为"词法召回漏此条"，不影响权威条目——与向量伴生写同一边界
        if (lexicalEnabled) {
            try {
                lexicalIndex.ensureLlmCacheIndex(namespace);
                redis.jsonSetPath(docKey, "$.lex",
                        objectMapper.writeValueAsString(LexTerms.toLexText(entry.prompt())));
            } catch (Exception e) {
                log.debug("lex 伴生写失败（词法召回将漏此条，稠密通道不受影响）: docKey={} - {}",
                        docKey, e.getMessage());
            }
        }
        if (vector != null) {
            vectorIndex.ensureLlmCacheIndex(namespace);
            String vecKey = keys.llmCacheVectorKey(
                    namespace, vectorIndex.fingerprintHash(), entry.modelTag(), entry.promptHash());
            redis.hsetBinary(vecKey, "vec",
                    Vectors.toFloat32Bytes(vector));
            redis.hset(vecKey, "model", entry.modelTag());
            redis.expire(vecKey, ttlSeconds);
            redis.zadd(keys.llmCacheVectorIndexKey(namespace), expireAt, vecKey);
        }
        log.debug("LLM 缓存已写入 ns={} entry={} ttl={}s vector={}",
                namespace, entry.entryId(), ttlSeconds, vector != null);
    }

    @Override
    public Optional<LlmCacheEntry> get(String namespace, String modelTag, String promptHash) {
        String json = redis.get(keys.llmCacheKey(namespace, modelTag, promptHash));
        return Optional.ofNullable(decode(json));
    }

    @Override
    public List<LlmCacheRepository.ScoredKnn> searchKnn(String namespace, float[] queryVector, String modelTag, int limit) {
        try {
            // modelTag 恒参与 TAG 精确过滤（modelTag() 归一化后永不为 null）：
            // 未传 model 的条目 modelTag="none"，与显式 model 的条目互不命中——
            // 过滤器退化成 "*" 会让不同 model 维度互相命中
            String filter = "@model:{" + SearchQueryTranslator.quoteTag(modelTag) + "}";
            return redis.ftSearchKnnScored(
                    vectorIndex.llmCacheIndexName(namespace),
                    filter + "=>[KNN " + limit + " @vec $q]",
                    Vectors.toFloat32Bytes(queryVector),
                    limit)
                    .stream()
                    .map(sk -> new LlmCacheRepository.ScoredKnn(sk.key(),
                            Double.isNaN(sk.distance()) ? null : sk.distance()))
                    .toList();
        } catch (Exception e) {
            // 索引/检索链路故障：语义查找降级为 miss，精确命中路径不受影响
            log.warn("LLM 缓存 KNN 检索失败，降级仅精确匹配: ns={} - {}", namespace, e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public Optional<LlmCacheEntry> getByVecKey(String namespace, String vecKey) {
        // vecKey = ...:llmcache:vec:{fpHash}:{modelTag}:{promptHash} → 剥出后两段重组文档 key
        String marker = ":llmcache:vec:";
        int idx = vecKey.indexOf(marker);
        if (idx < 0) {
            return Optional.empty();
        }
        String[] parts = vecKey.substring(idx + marker.length()).split(":", 3);
        if (parts.length != 3) {
            return Optional.empty();
        }
        return get(namespace, parts[1], parts[2]);
    }

    @Override
    public List<String> searchLexical(String namespace, String modelTag, String query, int limit) {
        if (!lexicalEnabled) {
            return List.of();
        }
        try {
            // OR 语义 + BM25 排序：词法通道负责召回面，精度由 RRF 排名与精判门兜底
            String terms = LexTerms.orQuery(query);
            if (terms == null) {
                return List.of();
            }
            lexicalIndex.ensureLlmCacheIndex(namespace);
            // 别名与 LexicalIndexManager.ensureLlmCacheIndex 的 SCHEMA 声明一致（$.modelTag AS modelTag）
            String filter = "@modelTag:{" + SearchQueryTranslator.quoteTag(modelTag) + "}";
            return redis.ftSearchRank(lexicalIndex.llmCacheLexIndexName(namespace),
                    filter + " @lex:(" + terms + ")", limit);
        } catch (Exception e) {
            log.warn("LLM 缓存词法召回失败（跳过词法通道）: ns={} - {}", namespace, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Optional<LlmCacheEntry> getByDocKey(String namespace, String docKey) {
        if (docKey == null || !docKey.contains(":llmcache:")) {
            return Optional.empty();
        }
        return Optional.ofNullable(decode(redis.get(docKey)));
    }

    @Override
    public long count(String namespace) {
        // 索引计数，O(1)。SCAN iris:{ns}:llmcache:*:* 的代价与 keyspace
        // 总量成正比（366 万键下单次约 7 秒），且本方法在每次写缓存的请求路径上被无条件调用。
        String indexKey = keys.llmCacheIndexKey(namespace);
        purgeExpired(indexKey);
        return redis.zcard(indexKey);
    }

    @Override
    public int evictTo(String namespace, int keepEntries) {
        String indexKey = keys.llmCacheIndexKey(namespace);
        String vectorIndexKey = keys.llmCacheVectorIndexKey(namespace);
        purgeExpired(indexKey);
        purgeExpired(vectorIndexKey);
        long total = redis.zcard(indexKey);
        if (total <= keepEntries) {
            return 0;
        }
        // 按分数（= 过期时刻）升序取受害者：即"剩余 TTL 最短优先淘汰"
        // （与查询语义缓存同策略：快过期的本来就快没了），但是 O(logN) 而非全库 SCAN。
        List<String> victims = redis.zrange(indexKey, 0, total - keepEntries - 1);
        if (victims.isEmpty()) {
            return 0;
        }
        Set<String> victimSuffixes = new HashSet<>();
        for (String docKey : victims) {
            victimSuffixes.add(docSuffix(docKey));
        }
        // 受害者文档一次 DEL（逐条是 N 次往返）
        redis.del(victims.toArray(String[]::new));
        redis.zrem(indexKey, victims.toArray(String[]::new));
        removeVectorsBySuffixes(vectorIndexKey, victimSuffixes);
        log.info("LLM 缓存容量淘汰 ns={} 删除={} 剩余={}", namespace, victims.size(), keepEntries);
        return victims.size();
    }

    /** 惰性清理索引里"过期时刻已到"的悬空成员（ZSET 无逐成员 TTL，见类注释）。 */
    private void purgeExpired(String indexKey) {
        redis.zremrangebyscore(indexKey, "-inf", String.valueOf(System.currentTimeMillis()));
    }

    /** 文档 key → 后两段后缀（{@code modelTag:promptHash}），向量 key 以同两段结尾。 */
    private static String docSuffix(String docKey) {
        int idx = docKey.indexOf(":llmcache:");
        return idx < 0 ? docKey : docKey.substring(idx + ":llmcache:".length());
    }

    /**
     * 批量删除一组文档对应的全部指纹向量（在向量索引内匹配，不 SCAN 全库）。
     *
     * <p>向量 key 是四段式 {@code vec:{fpHash}:{modelTag}:{promptHash}}，
     * 一个文档可能有多份指纹副本，故按后两段后缀匹配；候选集来自向量索引
     * （成员数 ≤ max-entries），成本与缓存规模成正比而非与 keyspace 成正比。
     */
    private void removeVectorsBySuffixes(String vectorIndexKey, Set<String> victimSuffixes) {
        if (victimSuffixes.isEmpty()) {
            return;
        }
        List<String> hits = new ArrayList<>();
        for (String vecKey : redis.zrange(vectorIndexKey, 0, -1)) {
            int idx = vecKey.indexOf(":llmcache:vec:");
            if (idx < 0) {
                continue;
            }
            String[] parts = vecKey.substring(idx + ":llmcache:vec:".length()).split(":", 3);
            if (parts.length == 3 && victimSuffixes.contains(parts[1] + ":" + parts[2])) {
                hits.add(vecKey);
            }
        }
        if (hits.isEmpty()) {
            return;
        }
        // 命中向量一次 DEL（逐条是 N 次往返）
        redis.del(hits.toArray(String[]::new));
        redis.zrem(vectorIndexKey, hits.toArray(String[]::new));
        log.debug("LLM 缓存向量已清理 索引={} 向量数={}", vectorIndexKey, hits.size());
    }

    @Override
    public long clear(String namespace) {
        // 这里<b>刻意保留 SCAN</b>：clear 是显式运维操作（手工清缓存），
        // 不挂在请求路径上，可以承受全库遍历；换来的是它能顺带清掉"尚未入索引"
        // 的历史条目——否则 DELETE /llm-cache 会残留旧答案，
        // 清空后仍可能命中陈旧答案。
        List<String> docs = docKeys(namespace);
        List<String> vecs = redis.scanKeys(keys.llmCacheVectorScanPattern(namespace));
        // 一次 multi-key DEL：逐条 DEL 是 N 次往返（与 removeVectorsBySuffixes 同口径；
        // 上限 ≈ max-entries，从 ≤1000 次往返收敛为 2 次）
        if (!docs.isEmpty()) {
            redis.del(docs.toArray(String[]::new));
        }
        if (!vecs.isEmpty()) {
            redis.del(vecs.toArray(String[]::new));
        }
        redis.del(keys.llmCacheIndexKey(namespace));
        redis.del(keys.llmCacheVectorIndexKey(namespace));
        log.info("LLM 缓存已清空 ns={} docs={} vectors={}（含索引）",
                namespace, docs.size(), vecs.size());
        return docs.size();
    }

    /**
     * namespace 下全部<b>文档</b> key（剔除向量 key）。仅供 {@link #clear} 使用。
     *
     * <p>SCAN 模式 {@code iris:{ns}:llmcache:*:*} 里的 {@code *} 会跨段匹配，
     * 向量 key（{@code ...:llmcache:vec:{fp}:{tag}:{hash}}）同样落入结果——
     * glob 无法表达"恰好两段"，这里按 {@code :llmcache:vec:} 显式排除，
     * 否则向量 key 会被误计为文档。
     */
    private List<String> docKeys(String namespace) {
        List<String> result = new ArrayList<>();
        for (String key : redis.scanKeys(keys.llmCacheKey(namespace, "*", "*"))) {
            if (!key.contains(":llmcache:vec:")) {
                result.add(key);
            }
        }
        return result;
    }

    /** 序列化条目；字段都是简单类型，失败只能是容器异常，直接抛出让写入显式失败。 */
    private String encode(LlmCacheEntry entry) {
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("entryId", entry.entryId());
            doc.put("modelTag", entry.modelTag());
            doc.put("promptHash", entry.promptHash());
            doc.put("prompt", entry.prompt());
            doc.put("response", entry.response());
            doc.put("model", entry.model() == null ? "" : entry.model());
            doc.put("createdAt", entry.createdAt());
            // 数据版本围栏：依赖实体 → 写入时版本；null 不写（兼容旧条目）
            if (entry.dependencies() != null) {
                doc.put("dependencies", entry.dependencies());
            }
            return objectMapper.writeValueAsString(doc);
        } catch (Exception e) {
            throw new IllegalStateException("LLM 缓存条目序列化失败", e);
        }
    }

    /** 反序列化单条；损坏条目按不存在处理（过期淘汰会最终清理）。 */
    private LlmCacheEntry decode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, LlmCacheEntry.class);
        } catch (Exception e) {
            log.warn("LLM 缓存条目反序列化失败，按 miss 处理: {}", e.getMessage(), e);
            return null;
        }
    }
}

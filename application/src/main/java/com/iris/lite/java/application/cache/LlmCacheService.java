package com.iris.lite.java.application.cache;

import com.iris.lite.java.application.query.EntityVersionService;
import com.iris.lite.java.application.query.SemanticCachedEntityQueryService;
import com.iris.lite.java.application.rag.MultiQueryExpander;
import com.iris.lite.java.application.rerank.CrossEncoderReranker;
import com.iris.lite.java.cache.LlmCacheEntry;
import com.iris.lite.java.cache.LlmCacheRepository;
import com.iris.lite.java.cache.embedder.Embedder;
import com.iris.lite.java.shared.error.ErrorCode;
import com.iris.lite.java.shared.error.IrisException;
import com.iris.lite.java.shared.metrics.IrisMetrics;
import com.iris.lite.java.shared.util.Digests;
import com.iris.lite.java.shared.util.RrfFusion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * LLM 响应语义缓存服务。
 *
 * <p><b>降本标准</b>：缓存 "prompt → LLM response" 对，
 * 相似 prompt 毫秒级返回已缓存响应，命中即免掉一次真实的 LLM 调用
 * （月省 = 月输出 token 成本 × 命中率）。与查询语义缓存
 * （{@code SemanticCachedEntityQueryService}，缓存的是 Query Engine 查询结果）
 * 是两个互补层：本服务面向 LLM 应用调用方，REST/MCP 直接可用。
 *
 * <p><b>两级命中</b>：
 * <ol>
 *   <li><b>精确</b>：promptHash 相同 → similarity=1.0 直取（不走向量）；</li>
 *   <li><b>语义</b>（三段式）：prompt 向量 KNN top-5 召回（余弦 ≥
 *       recallThreshold 0.85，宁可多召回、不可漏召回）→ <b>槽位一致性校验</b>（数字序列不同
 *       即拒绝——双塔与交叉编码器对槽位差异均不敏感，数字差异改写对的 rerank
 *       分可高达 0.9998，无阈值可分）→
 *       <b>交叉编码器重排</b>（rerank ≥ 阈值才通过；失效时保守 MISS）。
 *       与记忆去重同构：阈值判定放在应用层而非索引层。</li>
 * </ol>
 *
 * <p><b>model 维度绝不模糊</b>：modelTag 参与 TAG 精确过滤，
 * 不同模型的响应绝不互相命中；modelTag = SHA-256(model) 前 8 位，
 * model 为空用 "none"。
 */
@Service
public class LlmCacheService {

    private static final Logger log = LoggerFactory.getLogger(LlmCacheService.class);

    /** 响应长度上限：缓存不是文档存储，超长响应直接拒绝写入。 */
    private static final int MAX_RESPONSE_CHARS = 100_000;

    /** 数字槽位抽取：日期/数值/阈值类差异的确定性判定依据（见 slotConsistent）。 */
    private static final Pattern DIGIT_SLOTS = Pattern.compile("\\d+");

    private final LlmCacheRepository repository;
    private final ObjectProvider<Embedder> embedderProvider;
    private final ObjectProvider<CrossEncoderReranker> rerankerProvider;
    private final EntityVersionService versionService;
    private final MultiQueryExpander multiQueryExpander;
    private final FreshnessDetector freshnessDetector;
    private final boolean enabled;
    private final double threshold;
    private final long ttlSeconds;
    private final int maxEntries;
    private final int knnCandidates;
    private final double recallThreshold;
    private final double rerankThreshold;
    // RAG 多路召回：RRF 常数与融合候选池上限
    private final int rrfK;
    private final int ragCandidates;

    public LlmCacheService(
            LlmCacheRepository repository,
            ObjectProvider<Embedder> embedderProvider,
            ObjectProvider<CrossEncoderReranker> rerankerProvider,
            EntityVersionService versionService,
            MultiQueryExpander multiQueryExpander,
            FreshnessDetector freshnessDetector,
            @Value("${iris.llm-cache.enabled:true}") boolean enabled,
            // 0.90：LLM 响应错服务的代价远高于查询缓存，阈值取比 0.85 更保守的一档
            @Value("${iris.llm-cache.threshold:0.90}") double threshold,
            @Value("${iris.llm-cache.ttl-seconds:3600}") long ttlSeconds,
            @Value("${iris.llm-cache.max-entries:1000}") int maxEntries,
            // KNN 候选数：多取几个再按余弦筛，防 HNSW 近似排序把真命中挤掉
            @Value("${iris.llm-cache.knn-candidates:5}") int knnCandidates,
            // 召回阈值放宽（宁可多召回、不可漏召回），精度交给 重排门槛
            @Value("${iris.llm-cache.recall-threshold:0.85}") double recallThreshold,
            // rerank 分数（sigmoid 0-1）≥ 此值才判定命中，默认值需以评测集校准
            @Value("${iris.llm-cache.rerank.threshold:0.50}") double rerankThreshold,
            @Value("${iris.rag.rrf-k:60}") int rrfK,
            @Value("${iris.rag.candidates:12}") int ragCandidates) {
        this.repository = repository;
        this.embedderProvider = embedderProvider;
        this.rerankerProvider = rerankerProvider;
        this.versionService = versionService;
        this.multiQueryExpander = multiQueryExpander;
        this.freshnessDetector = freshnessDetector;
        this.enabled = enabled;
        this.threshold = threshold;
        this.ttlSeconds = ttlSeconds;
        this.maxEntries = maxEntries;
        this.knnCandidates = knnCandidates;
        this.recallThreshold = recallThreshold;
        this.rerankThreshold = rerankThreshold;
        this.rrfK = rrfK;
        this.ragCandidates = Math.max(1, ragCandidates);
    }

    /**
     * 查找结果：hit + 相似度 + 条目 + rerank 分数（仅语义命中且有重排时非空）；未命中带 reason。
     * miss 时若曾有候选进召回线但被重排拒绝，bestRejected* 携带其中最高 rerank 分（校准与排障用）。
     */
    public record LookupResult(boolean hit, boolean exact, double similarity,
                               Double rerankScore, LlmCacheEntry entry, String reason,
                               Double bestRejectedSimilarity, Double bestRejectedRerankScore) {
        static LookupResult of(LlmCacheEntry entry, boolean exact, double similarity) {
            return new LookupResult(true, exact, similarity, null, entry, null, null, null);
        }

        static LookupResult of(LlmCacheEntry entry, double similarity, double rerankScore) {
            return new LookupResult(true, false, similarity, rerankScore, entry, null, null, null);
        }

        static LookupResult miss(String reason) {
            return new LookupResult(false, false, 0, null, null, reason, null, null);
        }

        static LookupResult missRejected(String reason, double bestSim, double bestRerank) {
            return new LookupResult(false, false, 0, null, null, reason, bestSim, bestRerank);
        }
    }

    /**
     * 查找相似 prompt 的缓存响应。
     *
     * @param namespace    命名空间（缓存隔离边界）
     * @param prompt       用户 prompt
     * @param model        模型名（可空；参与精确过滤，不参与语义）
     * @param thresholdOverride 单次查找的阈值覆盖（0-1；null 用全局配置）
     * @param bypassFresh  实时性 bypass：true 时跳过全部缓存查找，
     *                     直接返回未命中——实时敏感问题由调用方主动绕过缓存
     */
    public LookupResult lookup(String namespace, String prompt, String model,
                               Double thresholdOverride, boolean bypassFresh) {
        requirePrompt(prompt);
        if (bypassFresh) {
            IrisMetrics.increment("iris.llmcache.requests", "result", "bypass");
            return LookupResult.miss("bypass");
        }
        // 时效词自动旁路：prompt 含「最新/现在/今天」类指示词 =
        // 问题语义锚定在说话时刻，任何缓存条目（历史快照）都可能已过期——
        // 无论调用方是否显式传 fresh，一律强制 miss。命中路径零成本检测
        // （确定性关键词），误伤方向（多一次真实 LLM 调用）比漏放（回放过期
        // 答案）安全。REST /lookup、MCP 工具、Agent 三条入口共用本必经点。
        if (freshnessDetector.isFreshSensitive(prompt)) {
            IrisMetrics.increment("iris.llmcache.requests", "result", "bypass");
            log.debug("时效词旁路 LLM 缓存 ns={} prompt 命中时效词表", namespace);
            return LookupResult.miss("fresh-keyword");
        }
        if (!enabled) {
            return LookupResult.miss("disabled");
        }
        String modelTag = modelTag(model);
        String promptHash = Digests.sha256Hex(prompt);

        // 1. 精确命中：同 prompt 同 model 直取；版本守卫不过则继续走语义通道
        Optional<LlmCacheEntry> exact = repository.get(namespace, modelTag, promptHash);
        if (exact.isPresent()) {
            if (isFresh(namespace, exact.get())) {
                log.debug("LLM 缓存精确命中 ns={} model={} hash={}", namespace, model, promptHash);
                IrisMetrics.increment("iris.llmcache.requests", "result", "hit-exact");
                return LookupResult.of(exact.get(), true, 1.0);
            }
            log.info("LLM 缓存精确命中但数据已过期（版本守卫拦截）ns={} hash={}", namespace, promptHash);
            IrisMetrics.increment("iris.llmcache.requests", "result", "fence-miss");
        }

        // 2. 语义命中（RAG 多路召回版）：稠密 KNN + 词法 BM25 → RRF 融合候选池
        //    → 版本守卫 → 槽位一致性 → 交叉编码器重排（批量）。
        // 背景：双塔余弦对微改写句对区分度不足（「9月6号有人下单吗」vs「那2025年的…」
        // cos 0.916 误命中），精度问题交给交叉编码器逐对判别；词法通道负责把
        // 稠密排名挤不进 top-K 的同义改写捞回候选池（RRF 融合，宁可多召回、不可漏召回）。
        Embedder embedder = embedderProvider.getIfAvailable();
        if (embedder == null) {
            IrisMetrics.increment("iris.llmcache.requests", "result", "miss");
            return LookupResult.miss("no-embedder");
        }
        CrossEncoderReranker reranker = rerankerProvider.getIfAvailable();
        float[] queryVec = embedder.embed(prompt);
        if (normSq(queryVec) == 0) {
            IrisMetrics.increment("iris.llmcache.requests", "result", "miss");
            return LookupResult.miss("empty-vector");
        }
        // 重排门槛生效时：召回线固定放宽（宁可多召回、不可漏召回），thresholdOverride 覆盖命中门槛；
        // 未启用重排时：thresholdOverride 覆盖余弦阈值
        double effectiveThreshold = reranker != null
                ? (thresholdOverride != null ? thresholdOverride : rerankThreshold)
                : (thresholdOverride != null ? thresholdOverride : threshold);
        double recallLine = reranker != null ? recallThreshold : effectiveThreshold;

        Set<String> tried = new HashSet<>();
        Map<String, Candidate> candidateMap = new LinkedHashMap<>();
        List<List<String>> channels = new ArrayList<>();
        List<String> denseRank = recallDense(namespace, modelTag, queryVec, knnCandidates, tried, candidateMap);
        if (!denseRank.isEmpty()) {
            channels.add(denseRank);
            IrisMetrics.increment("iris.rag.recall", "channel", "dense");
        }
        List<String> lexRank = recallLexical(namespace, modelTag, prompt, ragCandidates, tried, candidateMap);
        if (!lexRank.isEmpty()) {
            channels.add(lexRank);
            IrisMetrics.increment("iris.rag.recall", "channel", "lexical");
        }
        List<Candidate> pool = fusePool(rrfK, ragCandidates, channels, candidateMap);
        LookupResult result = evaluate(namespace, model, prompt, queryVec,
                embedder, reranker, effectiveThreshold, recallLine, pool);

        // 3. LLM 多查询升级通道（on-miss/always）：主链路未命中时改写变体再稠密
        //    召回一轮——命中路径零 LLM 延迟，miss 路径多 ~1s 换一次免真实调用。
        //    只在 no-match 时升级：stale（守卫拦截）与 rerank-error（重排失效）
        //    语义上不是"没找到"，升级没有意义
        if (!result.hit() && "no-match".equals(result.reason())
                && multiQueryExpander.shouldExpand(true)) {
            IrisMetrics.increment("iris.rag.multi-query", "result", "escalate");
            List<String> escalationRank = new ArrayList<>();
            for (String variant : multiQueryExpander.expand(prompt)) {
                escalationRank.addAll(recallDense(namespace, modelTag,
                        embedder.embed(variant), knnCandidates, tried, candidateMap));
            }
            if (!escalationRank.isEmpty()) {
                IrisMetrics.increment("iris.rag.recall", "channel", "multi-query");
                List<Candidate> escalated = fusePool(rrfK, ragCandidates,
                        List.of(escalationRank), candidateMap);
                result = evaluate(namespace, model, prompt, queryVec,
                        embedder, reranker, effectiveThreshold, recallLine, escalated);
            }
        }
        return result;
    }

    /** 融合候选：稠密距离已知者优先按其排序语义并入；RRF 只认通道排名，距离仅作相似度展示。 */
    private record Candidate(LlmCacheEntry entry, Double denseDistance) {
    }

    /** 稠密通道召回：KNN → 回捞条目 → promptHash 去重入池，返回 promptHash 排名（距离升序）。 */
    private List<String> recallDense(String namespace, String modelTag, float[] queryVec,
                                     int limit, Set<String> tried, Map<String, Candidate> pool) {
        List<String> rank = new ArrayList<>();
        for (LlmCacheRepository.ScoredKnn sk : repository.searchKnn(namespace, queryVec, modelTag, limit)) {
            LlmCacheEntry entry = repository.getByVecKey(namespace, sk.vecKey()).orElse(null);
            if (entry == null || !tried.add(entry.promptHash())) {
                continue; // 向量在文档已过期：静默跳过
            }
            pool.put(entry.promptHash(), new Candidate(entry, sk.distance()));
            rank.add(entry.promptHash());
        }
        return rank;
    }

    /** 词法通道召回：BM25 文档序 → 回捞条目 → promptHash 去重入池。失败/未启用返回空（fail-open）。 */
    private List<String> recallLexical(String namespace, String modelTag, String prompt,
                                       int limit, Set<String> tried, Map<String, Candidate> pool) {
        List<String> rank = new ArrayList<>();
        for (String docKey : repository.searchLexical(namespace, modelTag, prompt, limit)) {
            LlmCacheEntry entry = repository.getByDocKey(namespace, docKey).orElse(null);
            if (entry == null || !tried.add(entry.promptHash())) {
                continue;
            }
            pool.putIfAbsent(entry.promptHash(), new Candidate(entry, null));
            rank.add(entry.promptHash());
        }
        return rank;
    }

    /** RRF 融合：通道排名 → 融合候选池（原融合序，重排/纯余弦按此序先到先得）。 */
    private List<Candidate> fusePool(int rrfK, int topM, List<List<String>> channels,
                                     Map<String, Candidate> candidateMap) {
        return RrfFusion.fuse(rrfK, topM, channels).stream()
                .map(f -> candidateMap.get(f.id()))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 候选池重排：
     * 余弦召回线 → 版本守卫 → 槽位一致性 → rerank（批量一次调用）→ 过阈值通过。
     */
    private LookupResult evaluate(String namespace, String model, String prompt, float[] queryVec,
                                  Embedder embedder, CrossEncoderReranker reranker,
                                  double effectiveThreshold, double recallLine, List<Candidate> pool) {
        boolean fenceBlocked = false;
        double bestRejectedSim = -1;
        double bestRejectedRerank = -1;
        List<Candidate> survivors = new ArrayList<>();
        for (Candidate candidate : pool) {
            // 服务端 KNN 已算好余弦（distance = 1 - cos）——直接用，免去对每个
            // 候选重新 embedding（远程推理 × 候选数的纯冗余）；
            // 词法通道/无服务端分的候选回落本地重算，保证行为不劣化
            double similarity = candidate.denseDistance() != null
                    ? 1.0 - candidate.denseDistance()
                    : cosine(queryVec, embedder.embed(candidate.entry().prompt()));
            if (similarity < recallLine) {
                continue;
            }
            if (!isFresh(namespace, candidate.entry())) {
                // 版本守卫拦截：这条候选的数据版本已过期；继续看下一条候选
                // （其余候选可能声明了不同依赖、仍然新鲜）
                fenceBlocked = true;
                continue;
            }
            // 槽位一致性校验（先于 rerank 的确定性重排）：
            // 双塔余弦与交叉编码器对数字/日期槽位差异均不敏感——
            // 「…2025年的9月6号…」类改写对 rerank 可高达 0.9998（同义对
            // 0.999-1.0 完全重叠，无阈值可分）。数字序列不同则不可能是同一问题，
            // 直接拒绝；
            // 误拒方向是安全的（miss → 重算 → 答案仍正确，只损失一次省算）。
            if (!slotConsistent(prompt, candidate.entry().prompt())) {
                IrisMetrics.increment("iris.llmcache.rerank", "decision", "slot-reject");
                log.debug("槽位不一致拒绝 ns={} query槽位={} 候选槽位={}",
                        namespace, digitSlots(prompt), digitSlots(candidate.entry().prompt()));
                continue;
            }
            survivors.add(candidate);
        }
        if (survivors.isEmpty()) {
            if (fenceBlocked) {
                // 有候选过阈值但全部被守卫拦下：语义上"本可命中"，数据不新鲜导致 miss
                IrisMetrics.increment("iris.llmcache.requests", "result", "fence-miss");
                return LookupResult.miss("stale");
            }
            IrisMetrics.increment("iris.llmcache.requests", "result", "miss");
            return LookupResult.miss("no-match");
        }
        if (reranker == null) {
            // 重排门槛未启用：纯余弦判定（thresholdOverride 可覆盖），融合序先到先得
            Candidate hit = survivors.get(0);
            double similarity = candidateSimilarity(hit, queryVec, embedder);
            log.debug("LLM 缓存语义命中（无重排）ns={} model={} sim={}", namespace, model, similarity);
            IrisMetrics.increment("iris.llmcache.requests", "result", "hit-semantic");
            return LookupResult.of(hit.entry(), false, similarity);
        }
        // 重排门槛：交叉编码器批量判别（正向前向 1 次往返），融合序第一个过阈值者胜出
        double[] scores;
        try {
            scores = reranker.scoreBatch(prompt,
                    survivors.stream().map(c -> c.entry().prompt()).toList());
        } catch (Exception e) {
            // 重排失效时通过会重新引入误命中风险 → 保守 MISS 并明示
            log.warn("rerank 推理失败，保守降级为 MISS ns={} err={}", namespace, e.getMessage(), e);
            IrisMetrics.increment("iris.llmcache.requests", "result", "miss");
            IrisMetrics.increment("iris.llmcache.rerank", "decision", "error");
            return LookupResult.miss("rerank-error");
        }
        for (int i = 0; i < survivors.size(); i++) {
            Candidate candidate = survivors.get(i);
            double rerankScore = scores[i];
            IrisMetrics.increment("iris.llmcache.rerank", "decision",
                    rerankScore >= effectiveThreshold ? "pass" : "reject");
            if (rerankScore >= effectiveThreshold) {
                double similarity = candidateSimilarity(candidate, queryVec, embedder);
                log.debug("LLM 缓存语义命中（rerank 过门）ns={} model={} sim={} rerank={}",
                        namespace, model, similarity, String.format("%.4f", rerankScore));
                IrisMetrics.increment("iris.llmcache.requests", "result", "hit-semantic");
                return LookupResult.of(candidate.entry(), similarity, rerankScore);
            }
            // rerank 判不同义：记录被拒候选（校准/排障），继续看下一条候选
            if (rerankScore > bestRejectedRerank) {
                bestRejectedRerank = rerankScore;
                bestRejectedSim = candidateSimilarity(candidate, queryVec, embedder);
            }
        }
        IrisMetrics.increment("iris.llmcache.requests", "result", "miss");
        return bestRejectedRerank >= 0
                ? LookupResult.missRejected("no-match", bestRejectedSim, bestRejectedRerank)
                : LookupResult.miss("no-match");
    }

    /** 候选相似度取值：稠密通道有服务端距离直接用，词法/融合候选本地重算余弦。 */
    private static double candidateSimilarity(Candidate candidate, float[] queryVec, Embedder embedder) {
        return candidate.denseDistance() != null
                ? 1.0 - candidate.denseDistance()
                : cosine(queryVec, embedder.embed(candidate.entry().prompt()));
    }

    /**
     * 版本守卫校验：条目声明的依赖实体，其当前数据版本必须与写入时一致。
     * 未声明依赖的条目（旧数据/调用方未传）恒视为新鲜——守卫只约束显式声明者。
     */
    private boolean isFresh(String namespace, LlmCacheEntry entry) {
        if (entry.dependencies() == null || entry.dependencies().isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Long> dep : entry.dependencies().entrySet()) {
            long now = versionService.current(namespace, dep.getKey());
            if (now != dep.getValue()) {
                log.debug("版本守卫拦截 ns={} entity={} 写入时={} 当前={}",
                        namespace, dep.getKey(), dep.getValue(), now);
                return false;
            }
        }
        return true;
    }

    /**
     * 写入一条 prompt→response 缓存。
     *
     * @param ttlSeconds 覆盖 TTL（秒）；null 用全局配置
     * @param dependencyEntities 本条回答依赖的实体名（同 namespace，可空）。
     *                           声明后写入时记录各实体当前版本，命中即校验——
     *                           数据变更过的条目自动视为过期（数据版本守卫）
     * @return 条目标识（modelTag:promptHash）
     */
    public String store(String namespace, String prompt, String response,
                        String model, Long ttlSeconds, List<String> dependencyEntities) {
        requirePrompt(prompt);
        if (response == null || response.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "response 不能为空");
        }
        if (response.length() > MAX_RESPONSE_CHARS) {
            throw new IrisException(ErrorCode.INVALID_QUERY,
                    "response 超过长度上限 " + MAX_RESPONSE_CHARS);
        }
        if (!enabled) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "LLM 缓存未启用");
        }
        long effectiveTtl = ttlSeconds != null ? ttlSeconds : this.ttlSeconds;
        String modelTag = modelTag(model);
        String promptHash = Digests.sha256Hex(prompt);
        Embedder embedder = embedderProvider.getIfAvailable();
        float[] vector = embedder == null ? null : embedder.embed(prompt);
        if (vector != null && normSq(vector) == 0) {
            vector = null; // 空向量不进索引：退化为仅精确命中
        }
        // 依赖实体的当前版本快照（版本守卫的"存储侧半边"）
        Map<String, Long> dependencies = dependencyEntities == null || dependencyEntities.isEmpty()
                ? null
                : versionService.currentAll(namespace, dependencyEntities.stream()
                        .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList());
        LlmCacheEntry entry = new LlmCacheEntry(modelTag + ":" + promptHash, modelTag,
                promptHash, prompt, response, model, null, dependencies);
        repository.save(namespace, entry, vector, effectiveTtl);

        // 容量上限：写入后裁剪（淘汰剩余 TTL 最短条目，含其向量）
        long count = repository.count(namespace);
        if (count > maxEntries) {
            int evicted = repository.evictTo(namespace, maxEntries);
            if (evicted > 0) {
                IrisMetrics.increment("iris.llmcache.evictions", evicted);
            }
        }
        log.debug("LLM 缓存已写入 ns={} model={} hash={} ttl={}s deps={}",
                namespace, model, promptHash, effectiveTtl,
                dependencies == null ? 0 : dependencies.size());
        return entry.entryId();
    }

    /** 清空 namespace 下全部缓存，返回删除条数。 */
    public long clear(String namespace) {
        return repository.clear(namespace);
    }

    /**
     * 统计快照（控制台用）：命中/未命中计数 + 当前条目数 + 配置参数。
     *
     * <p>计数来自 Micrometer 累计值（进程生命周期内），条目数为实时值。
     * enabled=false 时请求计数恒 0——UI 应显示"未启用"而非 0 值。
     */
    public Map<String, Object> stats(String namespace) {
        long exact = Math.round(IrisMetrics.counterValue("iris.llmcache.requests", "result", "hit-exact"));
        long semantic = Math.round(IrisMetrics.counterValue("iris.llmcache.requests", "result", "hit-semantic"));
        long miss = Math.round(IrisMetrics.counterValue("iris.llmcache.requests", "result", "miss"));
        long fenceMiss = Math.round(IrisMetrics.counterValue("iris.llmcache.requests", "result", "fence-miss"));
        long bypass = Math.round(IrisMetrics.counterValue("iris.llmcache.requests", "result", "bypass"));
        long evictions = Math.round(IrisMetrics.counterValue("iris.llmcache.evictions"));
        long lookups = exact + semantic + miss;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("lookups", lookups);
        out.put("hitExact", exact);
        out.put("hitSemantic", semantic);
        out.put("hitTotal", exact + semantic);
        out.put("miss", miss);
        // 命中率：分母 0（从未查询）时返回 null 而非 0——区分"无数据"与"全未命中"
        out.put("hitRate", lookups == 0 ? null : (double) (exact + semantic) / lookups);
        // 守卫/旁路计数：被版本守卫拦截的命中数、调用方主动 bypass 次数
        out.put("fenceMiss", fenceMiss);
        out.put("bypass", bypass);
        out.put("evictions", evictions);
        // 重排门槛：通过/拒绝/失败计数与配置（rerankEnabled 反映 bean 是否装配）
        boolean rerankOn = rerankerProvider.getIfAvailable() != null;
        out.put("rerankEnabled", rerankOn);
        if (rerankOn) {
            out.put("rerankPassed", Math.round(IrisMetrics.counterValue("iris.llmcache.rerank", "decision", "pass")));
            out.put("rerankRejected", Math.round(IrisMetrics.counterValue("iris.llmcache.rerank", "decision", "reject")));
            out.put("rerankErrors", Math.round(IrisMetrics.counterValue("iris.llmcache.rerank", "decision", "error")));
            out.put("rerankSlotRejected", Math.round(IrisMetrics.counterValue("iris.llmcache.rerank", "decision", "slot-reject")));
            out.put("recallThreshold", recallThreshold);
            out.put("rerankThreshold", rerankThreshold);
        }
        out.put("entries", repository.count(namespace));
        out.put("threshold", threshold);
        out.put("ttlSeconds", ttlSeconds);
        out.put("maxEntries", maxEntries);
        return out;
    }

    // ---------- 工具 ----------

    private void requirePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "prompt 不能为空");
        }
    }

    /** model → TAG 段标识：空 = "none"，非空 = SHA-256 前 8 位（保留字符安全）。 */
    static String modelTag(String model) {
        if (model == null || model.isBlank()) {
            return "none";
        }
        return Digests.sha256Hex(model.trim()).substring(0, 8);
    }

    private static float normSq(float[] v) {
        float s = 0;
        for (float x : v) {
            s += x * x;
        }
        return s;
    }

    /**
     * 槽位一致性：两句中的数字序列（排序后）必须完全一致。
     * 覆盖日期（9月6号 vs 2025年的9月6号）、数量、金额阈值等差异类别；
     * 中文数字（九月六号）暂不识别——漏判方向是「同义被拒」，安全。
     */
    static boolean slotConsistent(String a, String b) {
        return Objects.equals(digitSlots(a), digitSlots(b));
    }

    private static List<String> digitSlots(String s) {
        if (s == null || s.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = DIGIT_SLOTS.matcher(s);
        while (m.find()) {
            out.add(m.group());
        }
        Collections.sort(out);
        return out;
    }

    /** 余弦相似度：Embedder 保证 L2 归一化，退化为点积；长度不一致按 0 处理（防御）。 */
    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        float dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}

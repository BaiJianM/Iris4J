package com.iris4j.application.memory;

import com.iris4j.cache.embedder.Embedder;
import com.iris4j.memory.LongTermMemory;
import com.iris4j.memory.MemoryRepository;
import com.iris4j.memory.MemoryType;
import com.iris4j.memory.SearchMode;
import com.iris4j.memory.WorkingMemoryDocument;
import com.iris4j.memory.strategy.Candidate;
import com.iris4j.memory.strategy.MemoryStrategy;
import com.iris4j.memory.strategy.MemoryStrategyRegistry;
import com.iris4j.memory.strategy.StrategyContext;
import com.iris4j.shared.util.Vectors;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 记忆自动晋升协调器：在后台异步完成会话记忆的 LLM 抽取与长期记忆写入。
 *
 * <p><b>为什么必须有这个类、且抽取必须异步</b>：LLM 推理是秒级阻塞调用，
 * 若在 saveWorkingMemory 的请求线程里同步抽取，每次写工作记忆都要多等 1~2 秒。
 * 本项目为单体部署，做法是<b>进程内可配线程数的 worker 池 + 有界队列</b>，
 * 请求线程只做"入队"这一个动作。
 *
 * <p><b>策略分发</b>：抽取时按
 * "会话级配置（工作记忆文档 strategy 字段）→ 全局缺省
 * {@code iris.memory.extractor.strategy}"定位策略，交给注册表执行。
 *
 * <p><b>去重设计（两级）</b>：候选写入前先比对 memoryHash（内容 SHA-256，
 * 精确重复零成本拦截），再 KNN 取相似条目算余弦——超过 dedup-threshold
 * 视为已存在跳过。向量去重应对 LLM 改写措辞，hash 拦截逐字重复。
 *
 * <p><b>降级契约</b>：extractor 未装配（未启用/LLM 未配置）时本类是纯 no-op。
 */
@Service
public class MemoryExtractionService {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionService.class);

    private final MemoryRepository repository;
    private final ObjectProvider<MemoryStrategyRegistry> registryProvider;
    private final Embedder embedder;
    private final boolean enabled;
    private final int triggerEvery;
    private final double dedupThreshold;
    private final String defaultStrategy;
    private final ExecutorService worker;

    public MemoryExtractionService(
            MemoryRepository repository,
            ObjectProvider<MemoryStrategyRegistry> registryProvider,
            Embedder embedder,
            @Value("${iris.memory.extractor.enabled:false}") boolean enabled,
            @Value("${iris.memory.extractor.trigger-every:6}") int triggerEvery,
            @Value("${iris.memory.extractor.dedup-threshold:0.90}") double dedupThreshold,
            @Value("${iris.memory.extractor.strategy:discrete}") String defaultStrategy,
            @Value("${iris.memory.extractor.threads:1}") int threads) {
        this.repository = repository;
        this.registryProvider = registryProvider;
        this.embedder = embedder;
        this.enabled = enabled;
        this.triggerEvery = Math.max(1, triggerEvery);
        this.dedupThreshold = dedupThreshold;
        this.defaultStrategy = defaultStrategy;
        // 可配线程数的 worker 池 + 有界队列：抽取任务天然允许丢弃（下次触发补抽），
        // 绝不无界堆积拖垮 JVM。DiscardPolicy 静默丢弃，入队侧 catch 记 warn。
        int poolSize = Math.max(1, threads);
        AtomicInteger seq = new AtomicInteger();
        this.worker = new ThreadPoolExecutor(
                poolSize, poolSize, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "iris-memory-worker-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                // 丢弃策略必须带日志：裸 DiscardPolicy 静默吞任务，下方 catch
                // RejectedExecutionException 永不可达，「队列已满」完全不可观测
                new ThreadPoolExecutor.DiscardPolicy() {
                    @Override
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                        log.warn("记忆抽取队列已满，本次触发丢弃（下个触发点会带上全部会话记忆补抽）");
                    }
                });
        if (enabled && registryProvider.getIfAvailable() != null) {
            log.info("记忆自动晋升已启用 strategy={} trigger-every={} dedup-threshold={} threads={}",
                    defaultStrategy, this.triggerEvery, dedupThreshold, poolSize);
        } else {
            log.info("记忆自动晋升未生效（enabled={} 策略注册表装配={}），所有抽取调用为 no-op",
                    enabled, registryProvider.getIfAvailable() != null);
        }
    }

    /** 触发钩子是否处于生效态（DefaultMemoryService 据此决定是否为钩子读文档）。 */
    public boolean hookActive() {
        return active();
    }

    /** 指标暴露：api 层 ExecutorMetricsConfig 据此注册 Micrometer gauge（模块纪律：application 不依赖 micrometer）。 */
    public ExecutorService workerExecutor() {
        return worker;
    }

    /**
     * 工作记忆追加后的自动触发钩子（由 DefaultMemoryService 调用，请求线程上执行）。
     * 本方法<b>只做计数判断和入队</b>——主请求耗时无感知的保证点。
     * document 由调用方单次读取传入，避免本钩子再独立全量读一遍（长会话每条 append 都是 O(n)）。
     */
    public void onWorkingMemoryAppended(String namespace, String sessionId, WorkingMemoryDocument document) {
        if (!active()) {
            return;
        }
        int size = document.entries().size();
        if (size == 0 || size % triggerEvery != 0) {
            return;
        }
        log.info("工作记忆达到触发阈值 ns={} session={} 条数={}，提交异步抽取",
                namespace, sessionId, size);
        submit(() -> extractAndStore(namespace, sessionId, null));
    }

    /**
     * 手动触发一次抽取（同步执行），返回本次新写入的长期记忆。
     * custom 策略可携带自定义 prompt；其余策略忽略该参数。
     */
    public List<LongTermMemory> extractNow(String namespace, String sessionId, String customPrompt) {
        if (!active()) {
            return List.of();
        }
        return extractAndStore(namespace, sessionId, customPrompt);
    }

    /** 是否真正启用（开关开 + 策略注册表已装配），任一不满足即 no-op。 */
    private boolean active() {
        return enabled && registryProvider.getIfAvailable() != null;
    }

    /** 向 worker 提交任务；队列满（worker 忙）时记 warn 丢弃——下次触发会补抽。 */
    private void submit(Runnable task) {
        try {
            worker.submit(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    // worker 线程内的兜底：任何异常只记日志，绝不让线程死掉
                    log.warn("记忆抽取任务执行失败: {}", e.getMessage(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("记忆抽取队列已满，本次触发丢弃（下个触发点会带上全部会话记忆补抽）");
        }
    }

    /**
     * 抽取主流程：定位策略 → 拉会话文本 → LLM 抽取（失败重试 1 次）→
     * 逐条两级去重 → 写入长期记忆。
     */
    private List<LongTermMemory> extractAndStore(String namespace, String sessionId, String customPrompt) {
        WorkingMemoryDocument document = repository.getWorkingMemoryDocument(namespace, sessionId);
        if (document.entries().isEmpty()) {
            return List.of();
        }
        // 只从现存条目抽取，不含已摘要进 context 的旧内容——
        // context 里的信息此前轮次已抽过，重复进 LLM 只会增加去重压力
        StringBuilder text = new StringBuilder();
        for (var entry : document.entries()) {
            text.append(entry.content()).append('\n');
        }
        // 策略定位：会话级配置优先于全局缺省
        MemoryStrategyRegistry registry = registryProvider.getObject();
        String strategyName = document.strategy().isBlank() ? defaultStrategy : document.strategy();
        MemoryStrategy strategy = registry.get(strategyName);

        long start = System.currentTimeMillis();
        List<Candidate> candidates = extractWithRetry(strategy, text.toString(), namespace, sessionId, customPrompt);
        if (candidates.isEmpty()) {
            return List.of();
        }
        log.info("策略抽取完成 ns={} session={} strategy={} 输入条数={} 候选数={} 耗时={}ms",
                namespace, sessionId, strategy.name(), document.entries().size(),
                candidates.size(), System.currentTimeMillis() - start);

        // ownerId 层：抽取出的长期记忆继承会话归属（谁的记忆归谁）
        String owner = document.owner();
        List<LongTermMemory> stored = new ArrayList<>();
        for (Candidate candidate : candidates) {
            LongTermMemory memory = new LongTermMemory(
                    UUID.randomUUID().toString(),
                    namespace,
                    candidate.type().name().toLowerCase(),
                    candidate.text(),
                    System.currentTimeMillis(),
                    candidate.type(),
                    candidate.topics(),
                    candidate.entities(),
                    candidate.eventDate(),
                    strategy.name(),
                    null,
                    owner);
            if (isDuplicate(namespace, owner, candidate)) {
                log.debug("候选记忆与已有记忆重复，跳过 ns={} owner={} content={}",
                        namespace, owner, candidate.text());
                continue;
            }
            repository.saveLongTermMemory(memory);
            stored.add(memory);
            log.info("自动晋升新长期记忆 ns={} id={} strategy={} memoryType={} content={}",
                    namespace, memory.id(), strategy.name(), memory.memoryType(), memory.content());
        }
        return stored;
    }

    /** LLM 调用失败重试 1 次（解析失败返回空列表，重试无济于事）。 */
    private List<Candidate> extractWithRetry(
            MemoryStrategy strategy, String sessionText, String namespace,
            String sessionId, String customPrompt) {
        StrategyContext context = StrategyContext.of(namespace, sessionId, customPrompt, 5);
        try {
            return strategy.extract(sessionText, context);
        } catch (Exception first) {
            log.warn("策略 {} 抽取失败，重试一次: {}", strategy.name(), first.getMessage());
            try {
                return strategy.extract(sessionText, context);
            } catch (Exception second) {
                log.warn("策略 {} 重试仍失败，本次按无候选处理: {}",
                        strategy.name(), second.getMessage());
                return List.of();
            }
        }
    }

    /**
     * 两级去重：memoryHash 精确比对（逐字重复零成本拦截）→
     * KNN top-1 余弦相似度（改写措辞兜底），任一命中即跳过。
     *
     * <p><b>owner 隔离（ownerId 层）</b>：去重候选集按 owner 预过滤——
     * 只与同一 owner 的既有记忆比对，不同 owner 的相似记忆各自独立保留
     * （alice 的"喜欢寿司"不应挡掉 bob 的"喜欢寿司"）。hash 精确比对因此
     * 也无需自行参与 owner：候选集本身已隔离。
     *
     * <p>检索底层失败自动降级为关键词匹配（兜底路径），
     * 降级时命中与否都能给出合理结果，不影响本流程健壮性。
     */
    private boolean isDuplicate(String namespace, String owner, Candidate candidate) {
        try {
            List<LongTermMemory> top = repository.searchLongTermMemory(
                    namespace, owner, candidate.text(), 3, SearchMode.SEMANTIC);
            if (top.isEmpty()) {
                return false;
            }
            String candidateHash = new LongTermMemory(
                    "tmp", namespace, "tmp", candidate.text(), 1L).memoryHash();
            for (LongTermMemory existing : top) {
                if (existing.memoryHash() != null && existing.memoryHash().equals(candidateHash)) {
                    log.debug("候选记忆 hash 精确重复 ns={} owner={}", namespace, owner);
                    return true;
                }
            }
            double sim = Vectors.cosine(
                    embedder.embed(candidate.text()),
                    embedder.embed(top.get(0).content()));
            log.debug("去重比对 ns={} owner={} 候选='{}' 已有='{}' sim={}",
                    namespace, owner, candidate.text(), top.get(0).content(), sim);
            return sim >= dedupThreshold;
        } catch (Exception e) {
            // 去重环节故障时宁可多存不漏存（重复记忆可人工清理，漏记忆找不回来）
            log.warn("记忆去重比对失败，按非重复处理: {}", e.getMessage(), e);
            return false;
        }
    }

    /** 停机时优雅关闭 worker：等待在跑的抽取完成（最多 5 秒），不丢任务。 */
    @PreDestroy
    public void shutdown() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }
}

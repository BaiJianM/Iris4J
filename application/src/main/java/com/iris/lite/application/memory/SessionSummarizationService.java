package com.iris.lite.application.memory;

import com.iris.lite.memory.LlmClient;
import com.iris.lite.memory.MemoryRepository;
import com.iris.lite.memory.WorkingMemoryDocument;
import com.iris.lite.memory.WorkingMemoryEntry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话渐进摘要服务。
 *
 * <p><b>机制</b>：会话内容字符量超过阈值时，
 * 把<b>旧消息</b>交给 LLM 做增量摘要（旧摘要 + 被裁消息 → 新摘要，滚动演进），
 * 摘要写入工作记忆文档的 {@code context} 字段，只保留最近约 40% 的条目。
 * 效果：会话上下文被压进固定预算，不再无限增长。
 *
 * <p><b>token 计量的近似</b>：不引入 tiktoken 依赖，
 * 用<b>字符数近似</b>（中文场景 1 字≈1 token 量级，阈值本身可配，近似足够）。
 *
 * <p><b>并发模型</b>：触发判断在请求线程（只读，零成本）；摘要执行在
 * 独立单线程 worker（LLM 秒级阻塞不碰主链路）；写回前持会话短锁，
 * 与高频 append 互斥——锁拿不到就放弃本次，下个触发点重做（幂等）。
 *
 * <p><b>降级契约</b>：enabled=false 或无 LLM 时整体旁路。
 */
@Service
public class SessionSummarizationService {

    private static final Logger log = LoggerFactory.getLogger(SessionSummarizationService.class);

    private static final String SYSTEM_PROMPT =
            "你是会话摘要器。只输出摘要正文，不要任何解释、前后缀或代码围栏。";

    /** 渐进摘要 prompt。 */
    private static final String USER_PROMPT_TEMPLATE = """
            你在维护一份会话的滚动摘要。请把"已有摘要"与"新消息"合并为一份更新后的摘要：
            1. 保留主要主题、关键决策、用户偏好与重要上下文；
            2. 已有摘要中已被新消息覆盖/推翻的内容以新消息为准；
            3. 摘要不超过 500 词。

            已有摘要（可能为空）：
            %s

            新消息：
            %s""";

    private final MemoryRepository repository;
    private final ObjectProvider<LlmClient> llmProvider;
    private final boolean enabled;
    private final int thresholdChars;
    private final int keepPercent;
    private final ExecutorService worker;

    public SessionSummarizationService(
            MemoryRepository repository,
            ObjectProvider<LlmClient> llmProvider,
            @Value("${iris.memory.summarization.enabled:true}") boolean enabled,
            @Value("${iris.memory.summarization.threshold-chars:8000}") int thresholdChars,
            @Value("${iris.memory.summarization.keep-percent:40}") int keepPercent) {
        this.repository = repository;
        this.llmProvider = llmProvider;
        this.enabled = enabled;
        this.thresholdChars = Math.max(100, thresholdChars);
        this.keepPercent = Math.min(90, Math.max(10, keepPercent));
        AtomicInteger seq = new AtomicInteger();
        // 单线程 + 有界队列：摘要允许丢弃（下个触发点重做），绝不堆积
        this.worker = new ThreadPoolExecutor(
                1, 1, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "iris-memory-summarizer-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                // 丢弃策略必须带日志：裸 DiscardPolicy 静默吞任务，「队列已满」不可观测
                new ThreadPoolExecutor.DiscardPolicy() {
                    @Override
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                        log.warn("会话摘要队列已满，本次触发丢弃（下个触发点重做）");
                    }
                });
        if (enabled && llmProvider.getIfAvailable() != null) {
            log.info("会话渐进摘要已启用 threshold-chars={} keep-percent={}%", this.thresholdChars, this.keepPercent);
        } else {
            log.info("会话渐进摘要未生效（enabled={} LLM装配={}），旁路",
                    enabled, llmProvider.getIfAvailable() != null);
        }
    }

    /** 会话内容占用百分比（GET /working 的 usagePercent，达 100% 即触发过摘要）。 */
    public int usagePercent(WorkingMemoryDocument document) {
        return Math.min(100, document.totalChars() * 100 / thresholdChars);
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
     * 工作记忆追加后的触发钩子（请求线程，只做阈值判断与入队）。
     * 条目不足 2 条不触发（至少 2 条消息才有摘要意义）。
     * document 由调用方单次读取传入，避免本钩子再独立全量读一遍（长会话每条 append 都是 O(n)）。
     */
    public void onWorkingMemoryAppended(String namespace, String sessionId, WorkingMemoryDocument document) {
        if (!active()) {
            return;
        }
        if (document.entries().size() < 2 || document.totalChars() < thresholdChars) {
            return;
        }
        log.info("会话内容达到摘要阈值 ns={} session={} chars={}，提交异步摘要",
                namespace, sessionId, document.totalChars());
        submit(() -> summarize(namespace, sessionId));
    }

    /**
     * 摘要主流程（worker 线程执行）：
     * 持锁 → 重读文档复核阈值 → 从尾部保留 keep-percent → 旧消息增量摘要 → 写回。
     */
    private void summarize(String namespace, String sessionId) {
        if (!repository.tryWorkingMemoryLock(namespace, sessionId)) {
            log.warn("摘要未获会话锁，跳过本次（下个触发点重做）: session={}", sessionId);
            return;
        }
        try {
            WorkingMemoryDocument document = repository.getWorkingMemoryDocument(namespace, sessionId);
            List<WorkingMemoryEntry> entries = document.entries();
            // 持锁后复核：并发 append 可能已让内容变化，也可能已被其他轮次摘要过
            if (entries.size() < 2 || document.totalChars() < thresholdChars) {
                return;
            }
            // 从尾部向前累计保留条目（最近的内容原样保留），其余交给摘要
            int keepBudget = document.totalChars() * keepPercent / 100;
            int keptChars = 0;
            int keepFrom = entries.size();
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (keptChars >= keepBudget) {
                    keepFrom = i + 1;
                    break;
                }
                keptChars += entries.get(i).content().length();
                keepFrom = i;
            }
            // 至少保留 1 条
            if (keepFrom >= entries.size()) {
                keepFrom = entries.size() - 1;
            }
            List<WorkingMemoryEntry> toSummarize = entries.subList(0, keepFrom);
            List<WorkingMemoryEntry> kept = new ArrayList<>(entries.subList(keepFrom, entries.size()));
            if (toSummarize.isEmpty()) {
                return;
            }

            String newSummary = incrementalSummary(document.context(), toSummarize);
            if (newSummary == null) {
                return; // LLM 失败，文档保持原样，下个触发点重试
            }
            repository.replaceWorkingMemory(namespace, sessionId,
                    new WorkingMemoryDocument(newSummary, document.strategy(), kept));
            log.info("会话渐进摘要完成 ns={} session={} 摘要前={}条 摘要后保留={}条 contextChars={}",
                    namespace, sessionId, entries.size(), kept.size(), newSummary.length());
        } finally {
            repository.releaseWorkingMemoryLock(namespace, sessionId);
        }
    }

    /** 增量摘要：旧摘要 + 被裁消息 → 新摘要；LLM 失败返回 null（放弃本次）。 */
    private String incrementalSummary(String prevSummary, List<WorkingMemoryEntry> messages) {
        StringBuilder joined = new StringBuilder();
        for (WorkingMemoryEntry message : messages) {
            joined.append(message.content()).append('\n');
        }
        try {
            String summary = llmProvider.getObject().complete(
                    SYSTEM_PROMPT,
                    USER_PROMPT_TEMPLATE.formatted(
                            prevSummary.isBlank() ? "（空）" : prevSummary, joined.toString()));
            return summary == null || summary.isBlank() ? null : summary.trim();
        } catch (Exception e) {
            log.warn("会话摘要 LLM 调用失败，保持原文档: {}", e.getMessage(), e);
            return null;
        }
    }

    /** 摘要是否真正生效。 */
    private boolean active() {
        return enabled && llmProvider.getIfAvailable() != null;
    }

    private void submit(Runnable task) {
        try {
            worker.submit(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    log.warn("会话摘要任务执行失败: {}", e.getMessage(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("摘要队列已满，本次触发丢弃（下个触发点重做）");
        }
    }

    /** 停机时优雅关闭 worker。 */
    @PreDestroy
    public void shutdown() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(3, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }
}

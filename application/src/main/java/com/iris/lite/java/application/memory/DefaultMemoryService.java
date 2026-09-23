package com.iris.lite.java.application.memory;

import com.iris.lite.java.memory.LongTermMemory;
import com.iris.lite.java.memory.MemoryRepository;
import com.iris.lite.java.memory.MemoryType;
import com.iris.lite.java.memory.SearchMode;
import com.iris.lite.java.memory.WorkingMemoryDocument;
import com.iris.lite.java.memory.WorkingMemoryEntry;
import com.iris.lite.java.shared.error.ErrorCode;
import com.iris.lite.java.shared.error.IrisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 记忆服务默认实现：编排参数校验、模型组装与仓储调用，不包含任何 Redis 命令。
 *
 * <p><b>两类记忆的定位差异</b>：
 * <ul>
 *   <li><b>工作记忆</b>：绑定会话（namespace + sessionId），短期上下文，
 *       带滚动摘要（context）与会话级抽取策略；</li>
 *   <li><b>长期记忆</b>：跨会话持久化，按 namespace 隔离，支持策略化自动晋升。</li>
 * </ul>
 */
@Service
public class DefaultMemoryService implements MemoryService {

    private static final Logger log = LoggerFactory.getLogger(DefaultMemoryService.class);

    /** 检索返回条数上限：防止一次拉全量把响应撑爆。 */
    private static final int MAX_LIMIT = 50;

    private final MemoryRepository repository;

    /**
     * 自动晋升协调器。抽取（含 LLM 调用/去重/落库）全部由它在独立 worker
     * 线程完成，本服务在请求线程上只做一次入队调用。
     * 协调器内部自带开关与注册表缺失的 no-op 兜底，因此恒可注入。
     */
    private final MemoryExtractionService extractionService;

    /** 会话渐进摘要服务（内容超阈值时滚动摘要旧消息）。同样恒可注入、内部自兜底。 */
    private final SessionSummarizationService summarizationService;

    public DefaultMemoryService(
            MemoryRepository repository,
            MemoryExtractionService extractionService,
            SessionSummarizationService summarizationService) {
        this.repository = repository;
        this.extractionService = extractionService;
        this.summarizationService = summarizationService;
    }

    /**
     * 向会话的工作记忆追加一条，并联动两个异步触发钩子：
     * 记忆晋升（抽取）与会话渐进摘要——都是入队动作，请求线程零 LLM 调用。
     */
    @Override
    public String saveWorkingMemory(String namespace, String sessionId, String content) {
        return saveWorkingMemory(namespace, sessionId, content, null, null);
    }

    @Override
    public String saveWorkingMemory(String namespace, String sessionId, String content, String strategy) {
        return saveWorkingMemory(namespace, sessionId, content, strategy, null);
    }

    @Override
    public String saveWorkingMemory(String namespace, String sessionId, String content,
                                    String strategy, String owner) {
        requireNamespace(namespace);
        requireNonBlank(sessionId, "sessionId");
        // id 在应用层生成并随条目传入仓储：调用方（异步摘要回写）需要拿回 id 定位本条
        WorkingMemoryEntry entry = new WorkingMemoryEntry(
                UUID.randomUUID().toString(),
                requireNonBlank(content, "content"),
                System.currentTimeMillis(),
                owner);
        repository.appendWorkingMemory(namespace, sessionId, entry);
        if (strategy != null && !strategy.isBlank()) {
            // 会话级策略随写入一并设置（持锁读-改-写）
            repository.saveWorkingMemoryStrategy(namespace, sessionId, strategy.trim().toLowerCase());
        }
        // 触发钩子：抽取按条数阈值、摘要按内容量阈值，各自内部判断（均只入队，零 LLM 调用）。
        // 文档单次读取供两个钩子共用（避免每个钩子各自独立全量读：长会话每条 append
        // 两次 O(n) 读 + 两次反序列化）；两个钩子都未生效时不读（保持零额外开销）。
        if (extractionService.hookActive() || summarizationService.hookActive()) {
            WorkingMemoryDocument document = repository.getWorkingMemoryDocument(namespace, sessionId);
            extractionService.onWorkingMemoryAppended(namespace, sessionId, document);
            summarizationService.onWorkingMemoryAppended(namespace, sessionId, document);
        }
        return entry.id();
    }

    @Override
    public void updateWorkingMemoryEntry(String namespace, String sessionId,
                                         String entryId, String newContent) {
        requireNamespace(namespace);
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(entryId, "entryId");
        String content = requireNonBlank(newContent, "newContent");
        // 持锁读-改-写：与 append/会话摘要的整文档回写互斥（仓储层锁语义，防交错丢条目）
        if (!repository.tryWorkingMemoryLock(namespace, sessionId)) {
            log.debug("工作记忆条目更新放弃（锁被占用，下轮摘要钩子自会处理）ns={} session={}",
                    namespace, sessionId);
            return;
        }
        try {
            WorkingMemoryDocument doc = repository.getWorkingMemoryDocument(namespace, sessionId);
            List<WorkingMemoryEntry> updated = doc.entries().stream()
                    .map(e -> e.id().equals(entryId)
                            ? new WorkingMemoryEntry(e.id(), content, e.createdAt(), e.owner())
                            : e)
                    .toList();
            if (updated.equals(doc.entries())) {
                // 条目已不存在（被清理/并发摘要回写过）：静默跳过，不把丢失当错误
                log.debug("工作记忆条目更新跳过（条目不存在）ns={} session={} entry={}",
                        namespace, sessionId, entryId);
                return;
            }
            repository.replaceWorkingMemory(namespace, sessionId,
                    new WorkingMemoryDocument(doc.context(), doc.strategy(), doc.owner(), updated));
            log.debug("工作记忆条目已更新 ns={} session={} entry={} len={}",
                    namespace, sessionId, entryId, content.length());
        } finally {
            repository.releaseWorkingMemoryLock(namespace, sessionId);
        }
    }

    /**
     * 设置会话级记忆抽取策略。
     *
     * <p>空值合法：表示清除会话级覆盖、回落全局缺省（{@code iris.memory.extractor.strategy}）
     * ——抽取端点"临时覆盖后还原"路径依赖这一语义。
     */
    @Override
    public void saveWorkingMemoryStrategy(String namespace, String sessionId, String strategy) {
        requireNamespace(namespace);
        requireNonBlank(sessionId, "sessionId");
        repository.saveWorkingMemoryStrategy(namespace, sessionId,
                strategy == null ? "" : strategy.trim().toLowerCase());
    }

    /** 读取某会话的完整工作记忆文档（context + strategy + entries）。 */
    @Override
    public WorkingMemoryDocument getWorkingMemory(String namespace, String sessionId) {
        requireNamespace(namespace);
        requireNonBlank(sessionId, "sessionId");
        return repository.getWorkingMemoryDocument(namespace, sessionId);
    }

    /** 清空某会话的工作记忆。 */
    @Override
    public void clearWorkingMemory(String namespace, String sessionId) {
        requireNamespace(namespace);
        requireNonBlank(sessionId, "sessionId");
        repository.clearWorkingMemory(namespace, sessionId);
    }

    @Override
    public LongTermMemory saveLongTermMemory(String namespace, String type, String content) {
        return saveLongTermMemory(namespace, type, content, null);
    }

    /**
     * 手动写入一条带归属的长期记忆。
     *
     * <p>id 在这里生成（UUID）而非交给仓储——生成策略是应用层的决策，
     * 仓储只负责存储，职责清晰。手动写入的 memoryType 统一为 SEMANTIC
     * （episodic 带事件时刻语义，只能由抽取策略判定）。
     * owner 不在这里归一——null 交给模型紧凑构造器兜底 default。
     */
    @Override
    public LongTermMemory saveLongTermMemory(String namespace, String type, String content, String owner) {
        requireNamespace(namespace);
        LongTermMemory memory = new LongTermMemory(
                UUID.randomUUID().toString(),
                namespace,
                type == null || type.isBlank() ? "note" : type,
                requireNonBlank(content, "content"),
                System.currentTimeMillis(),
                MemoryType.SEMANTIC,
                null, null, null, "manual", null, owner);
        LongTermMemory saved = repository.saveLongTermMemory(memory);
        log.debug("长期记忆已写入 ns={} id={} type={} owner={}",
                namespace, saved.id(), saved.type(), saved.owner());
        return saved;
    }

    /** 删除一条长期记忆（本体 + 向量）。 */
    @Override
    public void deleteLongTermMemory(String namespace, String memoryId) {
        requireNamespace(namespace);
        requireNonBlank(memoryId, "memoryId");
        repository.deleteLongTermMemory(namespace, memoryId);
        log.debug("长期记忆已删除 ns={} id={}", namespace, memoryId);
    }

    /**
     * 检索长期记忆。
     *
     * <p>limit 非法（<=0）时取默认 10，超过上限时截断到 50——
     * 静默截断而非报错，因为这是"返回多少条"的软参数，不影响正确性。
     */
    @Override
    public List<LongTermMemory> searchLongTermMemory(String namespace, String query, int limit, SearchMode mode) {
        return searchLongTermMemory(namespace, null, query, limit, mode);
    }

    @Override
    public List<LongTermMemory> searchLongTermMemory(
            String namespace, String owner, String query, int limit, SearchMode mode) {
        requireNamespace(namespace);
        String q = query == null ? "" : query.trim();
        String effectiveOwner = owner == null || owner.isBlank() ? null : owner.trim();
        int effectiveLimit = limit <= 0 ? 10 : Math.min(limit, MAX_LIMIT);
        return repository.searchLongTermMemory(namespace, effectiveOwner, q, effectiveLimit, mode);
    }

    /** 工作记忆语义检索（会话内 KNN）。 */
    @Override
    public List<WorkingMemoryEntry> searchWorkingMemory(String namespace, String sessionId, String query, int limit) {
        requireNamespace(namespace);
        requireNonBlank(sessionId, "sessionId");
        requireNonBlank(query, "query");
        int effectiveLimit = limit <= 0 ? 5 : Math.min(limit, MAX_LIMIT);
        return repository.searchWorkingMemory(namespace, sessionId, query.trim(), effectiveLimit);
    }

    /**
     * 手动触发一次会话记忆抽取（同步执行，返回新写入的长期记忆）。
     * 抽取器未启用时返回空列表——软降级而非报错，调用方可统一处理。
     */
    @Override
    public List<LongTermMemory> extractFromSession(String namespace, String sessionId, String customPrompt) {
        requireNamespace(namespace);
        requireNonBlank(sessionId, "sessionId");
        return extractionService.extractNow(namespace, sessionId, customPrompt);
    }

    private void requireNamespace(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "namespace 不能为空");
        }
    }

    /** 校验必填字符串非空，并返回 trim 后的值（避免存进首尾空格的内容）。 */
    private String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, field + " 不能为空");
        }
        return value.trim();
    }
}

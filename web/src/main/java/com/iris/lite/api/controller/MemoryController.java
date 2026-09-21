package com.iris.lite.api.controller;

import com.iris.lite.api.dto.SaveLongTermMemoryRequest;
import com.iris.lite.api.dto.SaveWorkingMemoryRequest;
import com.iris.lite.application.memory.MemoryService;
import com.iris.lite.application.memory.SessionSummarizationService;
import com.iris.lite.memory.LongTermMemory;
import com.iris.lite.memory.SearchMode;
import com.iris.lite.memory.WorkingMemoryDocument;
import com.iris.lite.memory.WorkingMemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST 记忆入口。
 *
 * <p>工作记忆与长期记忆两套 API：前者按会话读写（含滚动摘要 context、
 * 会话级策略、语义检索），后者跨会话检索（semantic/keyword/hybrid 三模式）。
 */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private static final Logger log = LoggerFactory.getLogger(MemoryController.class);

    private final MemoryService memoryService;
    /** 摘要服务仅用于计算 usagePercent（GET /working 的占用指标）。 */
    private final SessionSummarizationService summarizationService;

    public MemoryController(MemoryService memoryService,
                            SessionSummarizationService summarizationService) {
        this.memoryService = memoryService;
        this.summarizationService = summarizationService;
    }

    /** 追加一条工作记忆到指定会话；body 可选 strategy 设置会话级抽取策略、可选 owner 设置会话归属。 */
    @PostMapping("/working")
    public Map<String, Object> saveWorking(@RequestBody SaveWorkingMemoryRequest req) {
        memoryService.saveWorkingMemory(req.namespace(), req.sessionId(), req.content(),
                req.strategy(), req.owner());
        log.debug("工作记忆已追加 ns={} session={}", req.namespace(), req.sessionId());
        return Map.of("saved", true);
    }

    /**
     * 读取指定会话的工作记忆文档。
     *
     * <p><b>响应结构</b>：
     * {@code {context: 滚动摘要, strategy: 会话策略, usagePercent: 内容占用%,
     * entries: [{id, content, createdAt}]}}。
     */
    @GetMapping("/working")
    public Map<String, Object> getWorking(
            @RequestParam String namespace,
            @RequestParam String sessionId) {
        WorkingMemoryDocument document = memoryService.getWorkingMemory(namespace, sessionId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("context", document.context());
        response.put("strategy", document.strategy());
        response.put("owner", document.owner());
        response.put("usagePercent", summarizationService.usagePercent(document));
        response.put("entries", document.entries());
        return response;
    }

    /** 清空指定会话的工作记忆（含语义索引向量）。 */
    @DeleteMapping("/working")
    public Map<String, Object> clearWorking(
            @RequestParam String namespace,
            @RequestParam String sessionId) {
        memoryService.clearWorkingMemory(namespace, sessionId);
        log.debug("工作记忆已清空 ns={} session={}", namespace, sessionId);
        return Map.of("cleared", true);
    }

    /** 工作记忆语义检索（会话内 KNN）。 */
    @GetMapping("/working/search")
    public List<WorkingMemoryEntry> searchWorking(
            @RequestParam String namespace,
            @RequestParam String sessionId,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int limit) {
        return memoryService.searchWorkingMemory(namespace, sessionId, query, limit);
    }

    /** 写入一条长期记忆，返回完整记忆（含生成的 id）；可选 owner 指定记忆归属。 */
    @PostMapping("/long-term")
    public LongTermMemory saveLongTerm(@RequestBody SaveLongTermMemoryRequest req) {
        LongTermMemory saved = memoryService.saveLongTermMemory(
                req.namespace(), req.type(), req.content(), req.owner());
        log.debug("长期记忆已写入 ns={} id={} type={} owner={}",
                saved.namespace(), saved.id(), saved.type(), saved.owner());
        return saved;
    }

    /** 删除一条长期记忆（本体 + 语义向量）。 */
    @DeleteMapping("/long-term")
    public Map<String, Object> deleteLongTerm(
            @RequestParam String namespace,
            @RequestParam String memoryId) {
        memoryService.deleteLongTermMemory(namespace, memoryId);
        log.debug("长期记忆已删除 ns={} id={}", namespace, memoryId);
        return Map.of("deleted", true);
    }

    /**
     * 检索长期记忆。
     *
     * <p>query 可空——为空时返回最近的记忆（按时间倒序）。
     * mode：semantic（KNN 语义）/ keyword（关键词包含）/ hybrid（混合，缺省）。
     * <b>mode 用 String 接收</b>：Spring 的枚举 RequestParam 转换大小写敏感
     * （传 "hybrid" 直接 400），自行解析可宽容大小写与非法值。
     * ownerId 可选（ownerId 层）：传入则只返回该归属的记忆（精确匹配），省略返回全部。
     */
    @GetMapping("/long-term/search")
    public List<LongTermMemory> searchLongTerm(
            @RequestParam String namespace,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "hybrid") String mode,
            @RequestParam(required = false) String ownerId) {
        SearchMode searchMode;
        try {
            searchMode = SearchMode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.debug("未知检索模式 '{}'，回落 hybrid", mode);
            searchMode = SearchMode.HYBRID;
        }
        return memoryService.searchLongTermMemory(namespace, ownerId, query, limit, searchMode);
    }

    /**
     * 手动触发指定会话的工作记忆抽取（同步执行并返回新写入的长期记忆）。
     * 可选 strategy 覆盖本次抽取策略；custom 策略可传 customPrompt。
     * 抽取器未启用时返回空列表。
     */
    @PostMapping("/extract")
    public Map<String, Object> extract(
            @RequestParam String namespace,
            @RequestParam String sessionId,
            @RequestParam(required = false) String strategy,
            @RequestParam(required = false) String customPrompt) {
        // strategy 参数仅影响本次：临时生效——先读原策略，抽取后还原
        WorkingMemoryDocument doc = memoryService.getWorkingMemory(namespace, sessionId);
        if (strategy != null && !strategy.isBlank()) {
            memoryService.saveWorkingMemoryStrategy(namespace, sessionId, strategy);
        }
        List<LongTermMemory> stored;
        try {
            stored = memoryService.extractFromSession(namespace, sessionId, customPrompt);
        } finally {
            if (strategy != null && !strategy.isBlank()) {
                // 还原会话原策略（空串 = 回落全局缺省）
                memoryService.saveWorkingMemoryStrategy(namespace, sessionId, doc.strategy());
            }
        }
        log.info("手动记忆抽取完成 ns={} session={} strategy={} 新增={}",
                namespace, sessionId, strategy, stored.size());
        return Map.of("extracted", stored.size(), "memories", stored);
    }
}

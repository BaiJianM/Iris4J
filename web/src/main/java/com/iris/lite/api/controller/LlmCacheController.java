package com.iris.lite.api.controller;

import com.iris.lite.api.dto.LlmCacheLookupRequest;
import com.iris.lite.api.dto.LlmCacheStoreRequest;
import com.iris.lite.application.cache.LlmCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 响应语义缓存入口。
 *
 * <p>典型用法：LLM 应用在调用模型前先
 * {@code POST /lookup}，命中即用缓存的响应（毫秒级、零 token 成本）；
 * 未命中则真实调用 LLM，拿到响应后 {@code PUT} 回存，供后续相似请求命中。
 */
@RestController
@RequestMapping("/api/v1/llm-cache")
public class LlmCacheController {

    private static final Logger log = LoggerFactory.getLogger(LlmCacheController.class);

    private final LlmCacheService llmCacheService;
    /** stats 端点未显式传 namespace 时的兜底：跟随全局默认配置（不硬编码任何具体空间）。 */
    private final String defaultNamespace;

    public LlmCacheController(LlmCacheService llmCacheService,
                              @Value("${iris.namespace:}") String defaultNamespace) {
        this.llmCacheService = llmCacheService;
        this.defaultNamespace = defaultNamespace;
    }

    /** 查找相似 prompt 的缓存响应；未命中 hit=false 并带 reason。 */
    @PostMapping("/lookup")
    public Map<String, Object> lookup(@RequestBody LlmCacheLookupRequest req) {
        LlmCacheService.LookupResult r = llmCacheService.lookup(
                req.namespace(), req.prompt(), req.model(), req.thresholdOverride(),
                Boolean.TRUE.equals(req.fresh()));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("hit", r.hit());
        resp.put("exact", r.exact());
        resp.put("similarity", r.similarity());
        // 重排门槛：语义命中时透出 rerank 分数（重排未启用/精确命中为 null）
        resp.put("rerankScore", r.rerankScore());
        if (r.hit()) {
            resp.put("entry", Map.of(
                    "prompt", r.entry().prompt(),
                    "response", r.entry().response(),
                    "model", r.entry().model() == null ? "" : r.entry().model(),
                    "createdAt", r.entry().createdAt()));
        } else {
            resp.put("reason", r.reason());
            // 候选进过召回线但被重排拒绝时，透出最高被拒 rerank 分（校准/排障）
            if (r.bestRejectedRerankScore() != null) {
                resp.put("bestRejectedSimilarity", r.bestRejectedSimilarity());
                resp.put("bestRejectedRerankScore", r.bestRejectedRerankScore());
            }
        }
        return resp;
    }

    /** 写入 prompt→response 缓存，返回条目标识。 */
    @PutMapping
    public Map<String, Object> store(@RequestBody LlmCacheStoreRequest req) {
        String entryId = llmCacheService.store(req.namespace(), req.prompt(), req.response(),
                req.model(), req.ttlSeconds(), req.dependencies());
        log.debug("LLM 缓存写入 ns={} deps={}", req.namespace(),
                req.dependencies() == null ? 0 : req.dependencies().size());
        return Map.of("stored", true, "entryId", entryId);
    }

    /** 清空 namespace 下全部缓存，返回删除条数。 */
    @DeleteMapping
    public Map<String, Object> clear(@RequestParam String namespace) {
        long removed = llmCacheService.clear(namespace);
        return Map.of("cleared", true, "removed", removed);
    }

    /**
     * 统计快照（控制台用）：命中/未命中计数、命中率、当前条目数与配置。
     * 未启用时 lookups=0、hitRate=null——UI 据此显示"未启用"而非 0 值。
     */
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(required = false) String namespace) {
        // 兜底跟随 iris.namespace 配置，不硬编码任何具体空间
        String ns = namespace == null || namespace.isBlank() ? defaultNamespace : namespace;
        return llmCacheService.stats(ns);
    }
}

package com.iris4j.api.controller;

import com.iris4j.api.dto.PutCacheRequest;
import com.iris4j.application.cache.CacheService;
import com.iris4j.application.cache.SemanticReindexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * REST 缓存入口（精确缓存 + 语义缓存重嵌入与索引切换）。
 */
@RestController
@RequestMapping("/api/v1/cache")
public class CacheController {

    private static final Logger log = LoggerFactory.getLogger(CacheController.class);

    private final CacheService cacheService;
    private final SemanticReindexService reindexService;

    public CacheController(CacheService cacheService, SemanticReindexService reindexService) {
        this.cacheService = cacheService;
        this.reindexService = reindexService;
    }

    /** 写入缓存条目。 */
    @PutMapping
    public Map<String, Object> put(@RequestBody PutCacheRequest req) {
        cacheService.put(req.namespace(), req.key(), req.value(), req.ttlSeconds());
        log.debug("缓存已写入 ns={} key={} ttl={}s", req.namespace(), req.key(), req.ttlSeconds());
        return Map.of("cached", true);
    }

    /**
     * 读取缓存条目。
     *
     * <p>未命中返回 404 而非 null——REST 语义上"资源不存在"就该是 404，
     * 返回 200 + null 会让调用方难以区分"没缓存"和"缓存了 null"。
     */
    @GetMapping
    public ResponseEntity<Object> get(
            @RequestParam String namespace,
            @RequestParam String key) {
        Optional<Object> value = cacheService.get(namespace, key, Object.class);
        return value.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 失效单个缓存条目。 */
    @DeleteMapping
    public Map<String, Object> invalidate(
            @RequestParam String namespace,
            @RequestParam String key) {
        cacheService.invalidate(namespace, key);
        log.debug("缓存已失效 ns={} key={}", namespace, key);
        return Map.of("invalidated", true);
    }

    /**
     * 语义缓存重嵌入：把旧指纹命名空间条目迁移到当前 Embedder 指纹命名空间（幂等）。
     *
     * <p>换 embedding 模型或维度后执行一次即可。返回迁移报告
     * （scanned/skipped/migrated/failed），可直接判断是否迁移干净。
     */
    @PostMapping("/semantic/reindex")
    public SemanticReindexService.ReindexReport reindexSemantic(
            @RequestParam String namespace,
            @RequestParam String entity) {
        log.debug("语义缓存重嵌入请求 ns={} entity={}", namespace, entity);
        return reindexService.reindex(namespace, entity);
    }
}

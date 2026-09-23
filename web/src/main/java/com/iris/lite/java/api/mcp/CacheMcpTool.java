package com.iris.lite.java.api.mcp;

import com.iris.lite.java.application.cache.CacheService;
import com.iris.lite.java.application.cache.SemanticReindexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * MCP 缓存工具入口，与 REST 共用同一个 {@link CacheService}。
 */
@Component
public class CacheMcpTool {

    private static final Logger log = LoggerFactory.getLogger(CacheMcpTool.class);

    private final CacheService cacheService;
    private final SemanticReindexService reindexService;
    private final McpOperatorGuard operatorGuard;

    public CacheMcpTool(CacheService cacheService, SemanticReindexService reindexService,
                        McpOperatorGuard operatorGuard) {
        this.cacheService = cacheService;
        this.reindexService = reindexService;
        this.operatorGuard = operatorGuard;
    }

    /**
     * 写入缓存条目。
     *
     * <p>返回文本而非 boolean：MCP 工具返回结构化布尔值时，
     * Agent 拿到的只是 true/false，缺少上下文；
     * 返回一句话能让 Agent 确认"写到了哪个 namespace/key"。
     */
    @McpTool(name = "cache_put", description = "写入一个精确缓存条目，ttlSeconds 秒后过期（默认 60）。")
    public String cachePut(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace,
            @McpToolParam(description = "缓存 key", required = true) String key,
            @McpToolParam(description = "缓存值（任意 JSON 对象）", required = true) Map<String, Object> value,
            @McpToolParam(description = "过期秒数，默认 60", required = false) Integer ttlSeconds) {
        operatorGuard.require("cache_put");
        cacheService.put(namespace, key, value, ttlSeconds == null ? 60 : ttlSeconds);
        log.debug("MCP 缓存写入 ns={} key={}", namespace, key);
        return "已缓存: " + namespace + "/" + key;
    }

    /**
     * 读取缓存条目。
     *
     * <p>未命中返回 null（而非抛异常）：MCP 场景下"没缓存"是正常结果，
     * 抛异常会让 Agent 误判为调用失败。
     */
    @McpTool(name = "cache_get", description = "读取一个精确缓存条目，未命中返回 null。")
    public Object cacheGet(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace,
            @McpToolParam(description = "缓存 key", required = true) String key) {
        return cacheService.get(namespace, key, Object.class).orElse(null);
    }

    /** 失效一个缓存条目。 */
    @McpTool(name = "cache_invalidate", description = "失效一个精确缓存条目。")
    public String cacheInvalidate(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace,
            @McpToolParam(description = "缓存 key", required = true) String key) {
        operatorGuard.require("cache_invalidate");
        cacheService.invalidate(namespace, key);
        log.debug("MCP 缓存失效 ns={} key={}", namespace, key);
        return "已失效: " + namespace + "/" + key;
    }

    /**
     * 语义缓存重嵌入迁移（幂等）。
     *
     * <p>换 embedding 模型或维度后执行一次即可。返回摘要文本含
     * scanned/skipped/migrated/failed 四个计数，可直接判断是否迁移干净。
     */
    @McpTool(name = "semantic_reindex",
            description = "语义缓存重嵌入：把旧 Embedder 指纹命名空间的语义条目重算向量后"
                    + "迁移到当前指纹命名空间并清理旧索引（幂等，可重复执行）。"
                    + "换 embedding 模型或维度后执行一次即可。")
    public String semanticReindex(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace,
            @McpToolParam(description = "实体名（须为 Schema 中已注册的实体）", required = true) String entity) {
        operatorGuard.require("semantic_reindex");
        SemanticReindexService.ReindexReport r = reindexService.reindex(namespace, entity);
        return "重嵌入完成: scanned=%d skipped=%d migrated=%d failed=%d (to=%s)"
                .formatted(r.scanned(), r.skipped(), r.migrated(), r.failed(), r.toFingerprint());
    }
}

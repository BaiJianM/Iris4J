package com.iris.lite.api.mcp;

import com.iris.lite.application.cache.LlmCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP LLM 响应缓存工具。
 *
 * <p>让 Agent 在调用 LLM 前先查缓存、拿到响应后回存——
 * 相似请求毫秒级返回缓存响应、降低 LLM 成本。
 */
@Component
public class LlmCacheMcpTool {

    private static final Logger log = LoggerFactory.getLogger(LlmCacheMcpTool.class);

    private final LlmCacheService llmCacheService;
    private final McpOperatorGuard operatorGuard;

    public LlmCacheMcpTool(LlmCacheService llmCacheService, McpOperatorGuard operatorGuard) {
        this.llmCacheService = llmCacheService;
        this.operatorGuard = operatorGuard;
    }

    /** 语义查找：相似 prompt 命中即返回缓存响应（model 维度精确匹配）。 */
    @McpTool(name = "llm_cache_lookup",
            description = "在 LLM 响应缓存中查找与 prompt 语义相似（或完全相同）的已缓存响应。"
                    + "命中可跳过真实 LLM 调用（省 token、毫秒级返回）；"
                    + "未命中请正常调用 LLM 并用 llm_cache_store 回存。"
                    + "涉及实时数据的提问请将 fresh 设为 true 绕过缓存。")
    public Object lookup(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace,
            @McpToolParam(description = "用户 prompt", required = true) String prompt,
            @McpToolParam(description = "模型名（可空；只做精确匹配，不同模型不互相命中）",
                    required = false) String model,
            @McpToolParam(description = "相似度阈值覆盖（0-1，缺省用服务端配置 0.90）",
                    required = false) Double threshold,
            @McpToolParam(description = "实时性 bypass：true 时跳过缓存直接返回未命中"
                    + "（实时敏感问题用），缺省 false", required = false) Boolean fresh) {
        LlmCacheService.LookupResult r = llmCacheService.lookup(
                namespace, prompt, model, threshold, Boolean.TRUE.equals(fresh));
        log.debug("MCP LLM 缓存查找 ns={} hit={} sim={}", namespace, r.hit(), r.similarity());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("hit", r.hit());
        resp.put("exact", r.exact());
        resp.put("similarity", r.similarity());
        if (r.hit()) {
            resp.put("response", r.entry().response());
            resp.put("model", r.entry().model() == null ? "" : r.entry().model());
            resp.put("cachedPrompt", r.entry().prompt());
        } else {
            resp.put("reason", r.reason());
        }
        return resp;
    }

    /** 写入 prompt→response 对（LLM 调用完成后回存）。 */
    @McpTool(name = "llm_cache_store",
            description = "把一次 LLM 调用的 prompt 与响应写入语义缓存，供后续相似 prompt 命中"
                    + "（官方 LangCache 的降本模式：命中即免掉输出 token 成本）。"
                    + "回答依赖业务数据的，务必通过 dependencies 声明依赖实体以启用数据版本围栏。")
    public String store(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace,
            @McpToolParam(description = "用户 prompt", required = true) String prompt,
            @McpToolParam(description = "LLM 响应内容", required = true) String response,
            @McpToolParam(description = "模型名（可空）", required = false) String model,
            @McpToolParam(description = "过期秒数（可空，默认 3600）", required = false) Long ttlSeconds,
            @McpToolParam(description = "回答依赖的实体名，逗号分隔（可空）；"
                    + "声明后数据变更会让该条目自动过期（数据版本围栏）",
                    required = false) String dependencies) {
        operatorGuard.require("llm_cache_store");
        List<String> deps = dependencies == null || dependencies.isBlank()
                ? List.of()
                : List.of(dependencies.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        String entryId = llmCacheService.store(namespace, prompt, response, model, ttlSeconds, deps);
        return "已缓存: " + namespace + "/" + entryId
                + (deps.isEmpty() ? "" : "（依赖: " + String.join(", ", deps) + "）");
    }

    /** 清空 namespace 下全部缓存。 */
    @McpTool(name = "llm_cache_clear",
            description = "清空指定 namespace 的全部 LLM 响应缓存（文档与语义向量），返回删除条数。")
    public String clear(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace) {
        operatorGuard.require("llm_cache_clear");
        long removed = llmCacheService.clear(namespace);
        return "已清空 " + namespace + " 的 LLM 缓存，删除 " + removed + " 条";
    }
}

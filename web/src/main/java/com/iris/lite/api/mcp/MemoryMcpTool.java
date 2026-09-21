package com.iris.lite.api.mcp;

import com.iris.lite.application.memory.MemoryService;
import com.iris.lite.memory.LongTermMemory;
import com.iris.lite.memory.SearchMode;
import com.iris.lite.memory.WorkingMemoryDocument;
import com.iris.lite.memory.WorkingMemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 记忆工具入口，与 REST 共用同一个 {@link MemoryService}。
 *
 * <p>Agent 通过这组工具实现"记住上下文"：短期上下文进工作记忆（带滚动摘要
 * 与会话级策略），长期偏好进长期记忆（semantic/keyword/hybrid 检索）。
 */
@Component
public class MemoryMcpTool {

    private static final Logger log = LoggerFactory.getLogger(MemoryMcpTool.class);

    private final MemoryService memoryService;
    private final McpOperatorGuard operatorGuard;

    public MemoryMcpTool(MemoryService memoryService, McpOperatorGuard operatorGuard) {
        this.memoryService = memoryService;
        this.operatorGuard = operatorGuard;
    }

    /**
     * 追加一条工作记忆到指定会话（短期上下文）。
     * strategy 参数可在写入时设置该会话的记忆抽取策略；
     * owner 参数设置会话归属（同一用户/实体应恒传同一 owner，多用户记忆隔离的依据）。
     */
    @McpTool(name = "save_working_memory",
            description = "向指定会话的工作记忆追加一条文本（短期上下文）。strategy 可选：discrete（默认，抽取离散事实）/summary（会话摘要）/preferences（用户偏好）/custom（自定义抽取）。owner 可选：记忆归属标识（用户/实体），为多用户隔离记忆——同一用户/实体的所有会话应恒传同一 owner 值，省略则归入 default。")
    public String saveWorkingMemory(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace,
            @McpToolParam(description = "会话 ID", required = true) String sessionId,
            @McpToolParam(description = "记忆文本", required = true) String content,
            @McpToolParam(description = "会话级记忆抽取策略，可省略", required = false) String strategy,
            @McpToolParam(description = "记忆归属的 ownerId（标识用户/实体，多用户隔离；同一用户恒传同一值），可省略，缺省归入 default", required = false) String owner) {
        operatorGuard.require("save_working_memory");
        memoryService.saveWorkingMemory(namespace, sessionId, content, strategy, owner);
        log.debug("MCP 工作记忆追加 ns={} session={} owner={}", namespace, sessionId, owner);
        return "已追加到工作记忆: " + namespace + "/" + sessionId;
    }

    /** 读取指定会话的工作记忆文档（含滚动摘要、会话归属与条目）。 */
    @McpTool(name = "get_working_memory",
            description = "读取指定会话的工作记忆：context 为滚动摘要（旧消息已被摘要压缩），entries 为现存条目列表，owner 为会话归属。")
    public Map<String, Object> getWorkingMemory(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace,
            @McpToolParam(description = "会话 ID", required = true) String sessionId) {
        WorkingMemoryDocument document = memoryService.getWorkingMemory(namespace, sessionId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("context", document.context());
        response.put("strategy", document.strategy());
        response.put("owner", document.owner());
        response.put("entries", document.entries());
        return response;
    }

    /** 工作记忆语义检索（会话内按语义捞最相关的条目）。 */
    @McpTool(name = "search_working_memory",
            description = "在指定会话的工作记忆内做语义检索，返回语义最相关的条目（无需翻阅全部消息）。")
    public List<WorkingMemoryEntry> searchWorkingMemory(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace,
            @McpToolParam(description = "会话 ID", required = true) String sessionId,
            @McpToolParam(description = "检索文本", required = true) String query,
            @McpToolParam(description = "最多返回条数，默认 5", required = false) Integer limit) {
        return memoryService.searchWorkingMemory(namespace, sessionId, query, limit == null ? 5 : limit);
    }

    /** 清空指定会话的工作记忆。 */
    @McpTool(name = "clear_working_memory", description = "清空指定会话的工作记忆。")
    public String clearWorkingMemory(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace,
            @McpToolParam(description = "会话 ID", required = true) String sessionId) {
        operatorGuard.require("clear_working_memory");
        memoryService.clearWorkingMemory(namespace, sessionId);
        log.debug("MCP 工作记忆清空 ns={} session={}", namespace, sessionId);
        return "已清空工作记忆: " + namespace + "/" + sessionId;
    }

    /**
     * 手动写入一条长期记忆（跨会话持久化）。返回完整记忆对象（含生成的 id）。
     */
    @McpTool(name = "save_long_term_memory",
            description = "手动写入一条长期记忆（跨会话持久化）。type 为自由分类标签（如 preference/background/constraint），可省略。owner 可选：记忆归属标识（用户/实体，多用户隔离）——同一用户/实体应恒传同一 owner 值，省略则归入 default。")
    public LongTermMemory saveLongTermMemory(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace,
            @McpToolParam(description = "记忆类型标签，可省略", required = false) String type,
            @McpToolParam(description = "记忆文本", required = true) String content,
            @McpToolParam(description = "记忆归属的 ownerId（标识用户/实体，多用户隔离；同一用户恒传同一值），可省略，缺省归入 default", required = false) String owner) {
        operatorGuard.require("save_long_term_memory");
        LongTermMemory saved = memoryService.saveLongTermMemory(namespace, type, content, owner);
        log.debug("MCP 长期记忆写入 ns={} id={} type={} owner={}",
                namespace, saved.id(), saved.type(), saved.owner());
        return saved;
    }

    /** 删除一条长期记忆。 */
    @McpTool(name = "delete_long_term_memory", description = "删除一条长期记忆（按 save 返回的 id）。")
    public String deleteLongTermMemory(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace,
            @McpToolParam(description = "记忆 id（save_long_term_memory 返回的 id）", required = true) String memoryId) {
        operatorGuard.require("delete_long_term_memory");
        memoryService.deleteLongTermMemory(namespace, memoryId);
        return "已删除长期记忆: " + memoryId;
    }

    /** 检索长期记忆（semantic/keyword/hybrid 三模式，可按 owner 过滤）。 */
    @McpTool(name = "search_long_term_memory",
            description = "检索长期记忆。mode：hybrid（默认，语义+关键词混合）/semantic（向量语义）/keyword（关键词包含）；query 为空返回最近记忆。owner 可选：传入则只检索该归属（用户/实体）的记忆——多用户场景检索时应传入当前用户/实体的 owner 以实现记忆隔离，省略则返回全部 owner 的记忆。")
    public List<LongTermMemory> searchLongTermMemory(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace,
            @McpToolParam(description = "检索文本，为空返回最近记忆", required = false) String query,
            @McpToolParam(description = "最多返回条数，默认 10，上限 50", required = false) Integer limit,
            @McpToolParam(description = "检索模式 hybrid/semantic/keyword，可省略", required = false) String mode,
            @McpToolParam(description = "只检索该 ownerId（用户/实体）的记忆，可省略（省略返回全部）", required = false) String owner) {
        SearchMode searchMode = parseMode(mode);
        return memoryService.searchLongTermMemory(
                namespace, owner, query, limit == null ? 10 : limit, searchMode);
    }

    /** 模式参数解析：空/非法回落 HYBRID（宽容解析，避免 Agent 传错值直接报错）。 */
    private SearchMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return SearchMode.HYBRID;
        }
        try {
            return SearchMode.valueOf(mode.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.debug("未知检索模式 '{}'，回落 hybrid", mode);
            return SearchMode.HYBRID;
        }
    }
}

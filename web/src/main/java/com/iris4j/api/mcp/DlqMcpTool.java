package com.iris4j.api.mcp;

import com.iris4j.application.ops.DlqAdminService;
import com.iris4j.application.ops.DlqEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP DLQ 工具入口，与 REST 共用同一个 {@link DlqAdminService}。
 *
 * <p>让 Agent 能自助处理毒消息：查看失败条目 -> 分析 error 原因
 * -> 提示人工修复 -> 重放。
 */
@Component
public class DlqMcpTool {

    private static final Logger log = LoggerFactory.getLogger(DlqMcpTool.class);

    private final DlqAdminService dlqAdminService;
    private final McpOperatorGuard operatorGuard;

    public DlqMcpTool(DlqAdminService dlqAdminService, McpOperatorGuard operatorGuard) {
        this.dlqAdminService = dlqAdminService;
        this.operatorGuard = operatorGuard;
    }

    /** 查看 DLQ 条目，可按 namespace/entity 过滤。 */
    @McpTool(name = "dlq_list", description = "查看 CDC 死信队列（DLQ）条目，可按 namespace/entity 过滤。")
    public List<DlqEntry> dlqList(
            @McpToolParam(description = "命名空间过滤（可省略时用服务端默认配置）", required = false) String namespace,
            @McpToolParam(description = "实体名过滤（可选）", required = false) String entity,
            @McpToolParam(description = "最大返回条数，默认 50", required = false) Integer limit) {
        List<DlqEntry> entries = dlqAdminService.list(namespace, entity, limit == null ? 50 : limit);
        log.debug("MCP DLQ 查询 ns={} entity={} 返回={}", namespace, entity, entries.size());
        return entries;
    }

    /**
     * 人工重放 DLQ。
     *
     * <p>返回成功条数文本。0 条不代表失败——可能根因未修复，
     * 需要结合 dlq_list 的 error 字段判断。
     */
    @McpTool(name = "dlq_replay", description = "人工重放 CDC 死信队列：按正常投影路径重放，成功条目从 DLQ 移除、失败条目保留，返回重放成功的条数。")
    public String dlqReplay(
            @McpToolParam(description = "命名空间过滤（可省略时用服务端默认配置）", required = false) String namespace,
            @McpToolParam(description = "实体名过滤（可选）", required = false) String entity) {
        operatorGuard.require("dlq_replay");
        long replayed = dlqAdminService.replay(namespace, entity);
        log.info("MCP DLQ 重放 ns={} entity={} 成功={}", namespace, entity, replayed);
        return "DLQ 重放成功条数: " + replayed;
    }
}

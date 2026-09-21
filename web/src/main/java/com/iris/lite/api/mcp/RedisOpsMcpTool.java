package com.iris.lite.api.mcp;

import com.iris.lite.application.ops.RedisOpsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * Redis 运维 MCP 工具（高可用/备份/演练），与 REST 共用 {@link RedisOpsService}。
 *
 * <p>返回格式化文本而非对象：MCP 场景下 Agent 直接读文本更省事，
 * 不必再解析嵌套结构。
 */
@Component
public class RedisOpsMcpTool {

    private static final Logger log = LoggerFactory.getLogger(RedisOpsMcpTool.class);

    private final RedisOpsService redisOpsService;
    private final McpOperatorGuard operatorGuard;

    public RedisOpsMcpTool(RedisOpsService redisOpsService, McpOperatorGuard operatorGuard) {
        this.redisOpsService = redisOpsService;
        this.operatorGuard = operatorGuard;
    }

    /**
     * Redis 运维快照。
     *
     * <p>注意 {@code .formatted} 的括号：它只作用于紧贴的字符串字面量，
     * 多段拼接必须整体加括号，否则只有最后一段参与格式化（踩过的坑）。
     */
    @McpTool(name = "redis_status",
            description = "Redis 运维快照：key 总数、内存、AOF/RDB 持久化状态、"
                    + "最近 RDB 落盘时间、自上次落盘以来的变更数（评估何时该备份）。")
    public String redisStatus() {
        var s = redisOpsService.snapshot();
        // 注意括号：.formatted 只作用于紧贴的字符串字面量，多段拼接必须整体加括号
        return ("dbsize=%d, version=%s, memory=%s, aof=%s(%s), rdb_last_save=%s, "
                + "rdb_bgsave_status=%s, changes_since_save=%d")
                .formatted(s.dbsize(), s.redisVersion(), s.usedMemoryHuman(),
                        s.aofEnabled() ? "on" : "off", s.aofLastWriteStatus(),
                        s.rdbLastSaveTime(), s.rdbLastBgsaveStatus(),
                        s.rdbChangesSinceLastSave());
    }

    /**
     * 触发 BGSAVE。
     *
     * <p>异步执行，返回后需再用 redis_status 确认 rdb_bgsave_status。
     */
    @McpTool(name = "redis_bgsave",
            description = "触发 Redis 后台 RDB 快照（BGSAVE），完成后可用 redis_status 确认。")
    public String redisBgsave() {
        operatorGuard.require("redis_bgsave");
        log.info("BGSAVE 触发请求（MCP）");
        redisOpsService.bgsave();
        return "BGSAVE 已触发";
    }
}

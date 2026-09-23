package com.iris.lite.java.api.mcp;

import com.iris.lite.java.api.security.AgentIdentity;
import com.iris.lite.java.api.security.AgentKeyRegistry;
import org.springframework.stereotype.Component;

/**
 * MCP 写/管理类工具的 operator 门禁。
 *
 * <p><b>背景</b>：REST 侧管理端点（AgentKeyAdminController / SchemaController /
 * RedisAdminController）都要求 operator 身份，MCP 工具同样执行该门禁——
 * 否则拿到合法 agent key 的第三方 Agent 可以直接重放 DLQ、写删记忆、
 * 重建索引、触发 BGSAVE。本类把 REST 侧的同一策略应用到 MCP 面。
 *
 * <p><b>策略（与 REST 侧逐字一致）</b>：
 * <ul>
 *   <li>鉴权关闭（本地开发默认）：通过——「匿名即 operator」是已文档化的
 *       本地语义（见 {@code StartupSecurityWarning} 的启动警告），否则 console
 *       演示开箱即碎；</li>
 *   <li>鉴权开启：身份必须持通配 tag {@code *}（legacy key）——per-agent key
 *       是数据访问身份，不是运维身份；</li>
 *   <li>鉴权开启但回调线程取不到身份（异步边界）：拒绝——fail-closed，
 *       与 {@link McpRequestIdentity} 的安全方向降级一致。</li>
 * </ul>
 *
 * <p><b>覆盖范围</b>：仅写/管理操作需要调用 {@link #require}；只读工具
 * （dlq_list、cache_get、redis_status 等）不需要——它们已受 API-Key 鉴权 +
 * 实体 access tags 治理链保护。
 */
@Component
public class McpOperatorGuard {

    private final AgentKeyRegistry registry;

    public McpOperatorGuard(AgentKeyRegistry registry) {
        this.registry = registry;
    }

    /**
     * 校验当前 MCP 调用者是否 operator；无权限直接抛异常（消息原样透传给 Agent）。
     *
     * @param action 操作名（如 {@code dlq_replay}），写进拒绝消息方便 Agent 自查
     */
    public void require(String action) {
        if (!registry.isAuthEnabled()) {
            return;
        }
        AgentIdentity identity = McpRequestIdentity.resolve();
        if (identity == null || !identity.hasTag(AgentIdentity.WILDCARD_TAG)) {
            throw new IllegalStateException(
                    "操作 " + action + " 需要 operator 身份（legacy key，通配 tag *）；"
                            + "当前身份"
                            + (identity == null ? "不可用（fail-closed）" : "=" + identity.agentId())
                            + "。只读操作不受影响。");
        }
    }
}

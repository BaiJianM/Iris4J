package com.iris4j.api.security;

import com.iris4j.application.query.AccessControlledEntityQueryService;
import java.util.List;
import java.util.Set;

/**
 * Agent 身份（access tags）——鉴权过滤器解析 API-Key 后写入 request attribute，
 * 查询链路据此做行级/字段级可见性裁剪。
 *
 * <p><b>为什么是独立 record 而非传裸 tags</b>：agentId 随审计日志一起落盘
 * （"哪个 agent 在什么时间查了什么"是访问治理的基本盘），裸 List 表达不了。
 *
 * @param agentId Agent 标识（来自配置 agents[].agent-id）
 * @param tags    Agent 持有的 access tags；空集合 = 未声明任何可见性授权
 *                （对声明了 accessTagField 的实体 fail-closed，公开实体不受影响）
 */
public record AgentIdentity(String agentId, List<String> tags) {

    /** request attribute 名。 */
    public static final String REQUEST_ATTRIBUTE = "iris.agent";

    /** API-Key 请求头名（过滤器读取与 controller 透传 MCP 会话共用）。 */
    public static final String API_KEY_HEADER = "X-API-Key";

    /**
     * 通配 tag {@code *}：operator 权限约定值——仅 legacy 兼容身份使用，
     * per-agent key 严禁配置（见 {@link #legacy()}）。字段级/行级可见性与
     * operator 门禁（REST 管理端点、MCP 写操作）都识别该约定。
     */
    public static final String WILDCARD_TAG = "*";

    public AgentIdentity {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /**
     * 旧单 key（{@code iris.security.api-key}）对应的兼容身份：全可见。
     *
     * <p>用通配 tag {@code "*"} 表达"不设限"——字段级（{@code FieldSchema.visibleTo}）
     * 与行级（{@code AccessControlledEntityQueryService}）都识别该约定。
     * 仅限内部兼容身份；per-agent 配置严禁使用。
     */
    public static AgentIdentity legacy() {
        return new AgentIdentity("legacy", List.of("*"));
    }

    /** tags 是否包含指定值。 */
    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    /** 不可变 tag 集合视图。 */
    public Set<String> tagSet() {
        return Set.copyOf(tags);
    }
}

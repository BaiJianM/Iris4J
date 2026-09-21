package com.iris.lite.api.mcp;

import com.iris.lite.api.security.AgentIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * MCP 工具回调里的 Agent 身份解析器（MCP 传输层身份接入）。
 *
 * <p><b>背景</b>：{@code ApiKeyAuthFilter} 对 {@code /mcp} 与 REST 走同一个
 * X-API-Key 校验入口，解析出的 {@link AgentIdentity} 已写入 request attribute——
 * 缺的只是 MCP 工具回调消费这一环。本类把"从当前线程取身份"集中一处。
 *
 * <p><b>线程假设</b>：Streamable-HTTP（webmvc transport）的工具回调在
 * servlet 请求线程上同步执行，{@link RequestContextHolder} 可用。
 * 若假设被打破（取不到 attributes/请求），返回 null——上层把 agentTags 置空，
 * 对声明 accessTagField 的实体走 fail-closed 空页（与 REST 无身份同语义），
 * 是<b>安全方向的降级</b>，不会放大可见性。
 */
public final class McpRequestIdentity {

    private static final Logger log = LoggerFactory.getLogger(McpRequestIdentity.class);

    private McpRequestIdentity() {
    }

    /**
     * 取当前请求的 Agent 身份；无身份（鉴权关闭/异步边界）返回 null。
     */
    public static AgentIdentity resolve() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra)) {
            log.debug("MCP 回调线程无请求上下文，身份按无处理（fail-closed 方向）");
            return null;
        }
        Object attr = sra.getRequest().getAttribute(AgentIdentity.REQUEST_ATTRIBUTE);
        return attr instanceof AgentIdentity a ? a : null;
    }
}

package com.iris4j.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris4j.api.web.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * API-Key 鉴权过滤器（支持 per-agent key + access tags 与动态 key 热载）。
 *
 * <ul>
 *   <li>REST（{@code /api/**}）与 Streamable-HTTP MCP（{@code /mcp}）共用同一个校验入口，
 *       请求头 {@code X-API-Key}；</li>
 *   <li>{@code /actuator/**} 豁免（服务健康状态是可观测性前提）；</li>
 *   <li><b>身份解析</b>：key -> 身份的映射委托
 *       {@link AgentKeyRegistry}（静态 yml 基线 + 动态 Redis 条目合并快照，
 *       动态条目增删改免重启热载）。命中则把 {@link AgentIdentity}
 *       （agentId + tags）写入 request attribute，供查询链路做行级/字段级
 *       可见性裁剪；未命中回落 legacy 单 key {@code iris.security.api-key}
 *       （agentId=legacy，全可见）；</li>
 *   <li>配置全部为空且无动态条目时鉴权关闭（本地开发默认）；
 *       key 经环境变量注入（yml 占位符），代码与配置文件均不硬编码明文；</li>
 *   <li>校验的常量时间比较/指纹精确匹配在注册表内完成，避免时序侧信道。</li>
 * </ul>
 *
 * <p><b>执行顺序</b>：{@code HIGHEST_PRECEDENCE + 10}——必须在任何业务过滤器之前，
 * 未通过鉴权的请求不应该接触到业务逻辑。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /** 统一错误码：鉴权失败（IRIS 模块 40xx 段）。 */
    static final String CODE_UNAUTHORIZED = "IRIS-4010";

    private final AgentKeyRegistry registry;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(AgentKeyRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    /**
     * 豁免判定：actuator 端点不走鉴权。
     *
     * <p>health/metrics 是监控系统探活用的，通常不方便带 API-Key。
     * 这些端点不暴露业务数据，豁免是安全与可运维性的合理权衡。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // actuator 豁免（health/metrics 本机监控用）；其余路径全部过校验
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!registry.isAuthEnabled()) {
            // 鉴权关闭（本地开发默认）。不打日志——每次请求都打毫无价值
            chain.doFilter(request, response);
            return;
        }

        String provided = request.getHeader(AgentIdentity.API_KEY_HEADER);
        AgentIdentity identity = registry.identityFor(provided);
        if (identity == null) {
            // 鉴权失败要带 URI 和方法：排查"哪个客户端在拿错 key"时这是唯一线索
            log.warn("API-Key 校验失败 uri={} method={} 原因={}",
                    request.getRequestURI(), request.getMethod(),
                    provided == null ? "缺失" : "无效");
            writeUnauthorized(response, provided == null ? "API-Key 缺失" : "API-Key 无效");
            return;
        }
        // 身份随请求向下传递：EntityQueryController 据此注入 agentTags
        request.setAttribute(AgentIdentity.REQUEST_ATTRIBUTE, identity);
        chain.doFilter(request, response);
    }

    /** 写 401 响应：统一走 ErrorResponse 结构，让调用方解析逻辑与业务错误一致。 */
    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                objectMapper.writeValueAsString(new ErrorResponse(CODE_UNAUTHORIZED, message)));
    }
}

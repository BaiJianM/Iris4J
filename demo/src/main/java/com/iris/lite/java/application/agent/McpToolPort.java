package com.iris.lite.java.application.agent;

import java.util.List;
import java.util.Map;

/**
 * MCP 客户端端口（演示页「MCP 按需发现」模式）：以真实外部 Agent 的身份
 * 接入本服务 Streamable-HTTP {@code /mcp} 端点——initialize 握手 → tools/list
 * → 循环 tools/call → 关闭会话。
 *
 * <p><b>为什么要绕一圈协议</b>：直连目录模式（AgentToolDispatcher）与 MCP
 * 面是两条链路；要让演示页完整触发按需发现（search_entity_tools →
 * call_entity_tool 两跳），模型必须看到 MCP 的真实 tools/list（25 个），
 * 工具执行必须走 tools/call 的协议路径——与 Claude Desktop 等外部接入完全一致，
 * 演示才有意义。
 *
 * <p><b>会话生命周期</b>：每次提问开一个会话、随答随关（编排层 try-with-resources），
 * 无跨请求状态，与外部客户端一次会话的行为一致。
 *
 * <p><b>身份</b>：调用方透传用户自己的 X-API-Key，MCP 侧鉴权/访问控制
 * 对演示页与外部 Agent 一视同仁。
 */
public interface McpToolPort {

    /** 打开一个 MCP 会话（initialize + notifications/initialized）。 */
    McpSession open(String apiKey) throws Exception;

    /** 单个 MCP 会话：工具目录 + 工具调用。非线程安全，单次问答内串行使用。 */
    interface McpSession extends AutoCloseable {

        /** tools/list：当前 MCP 工具面（on-demand 模式下 25 个，含 search/call 两件套）。 */
        ListOfTools listTools() throws Exception;

        /**
         * tools/call：执行一个工具，返回 content[0].text。
         * 业务错误（isError / JSON-RPC error）已归一为 {@code {"error":true,...}} JSON，
         * 与 AgentToolDispatcher 的错误约定一致，模型可读并自行修正参数。
         */
        String callTool(String name, String argumentsJson) throws Exception;
    }

    /** 工具目录条目（MCP tools/list 的 name/description/inputSchema 三件套）。 */
    record ToolInfo(String name, String description, Map<String, Object> inputSchema) {
    }

    /** tools/list 结果。 */
    record ListOfTools(List<ToolInfo> tools) {
    }
}

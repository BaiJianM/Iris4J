package com.iris.lite.infrastructure.mcp;

import com.iris.lite.application.agent.AgentToolDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.lite.application.agent.McpToolPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Streamable-HTTP MCP 客户端（{@link McpToolPort} 实现）：演示页 Agent 以
 * 真实外部 Agent 身份接入本服务 {@code /mcp}。
 *
 * <p><b>协议</b>：POST initialize（响应头取 mcp-session-id）→ POST
 * notifications/initialized → POST tools/list → 循环 POST tools/call →
 * DELETE 关闭会话。所有响应按 SSE 解析（Accept 必须含 text/event-stream），
 * 兼容纯 JSON 应答。业务错误（result.isError / JSON-RPC error）统一归一为
 * {@code {"error":true,"message":...}}，对齐 AgentToolDispatcher 的错误约定。
 *
 * <p><b>自连地址</b>：默认 {@code http://127.0.0.1:{server.port}/mcp}（同进程
 * 回环自调），可用 iris.agent.mcp-endpoint 覆盖指向独立部署的 MCP 端点。
 */
@Component
public class StreamableHttpMcpClient implements McpToolPort {

    private static final Logger log = LoggerFactory.getLogger(StreamableHttpMcpClient.class);

    private static final String PROTOCOL_VERSION = "2025-03-26";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper;
    private final String endpoint;

    public StreamableHttpMcpClient(ObjectMapper objectMapper,
                                   @Value("${server.port:8080}") int serverPort,
                                   @Value("${iris.agent.mcp-endpoint:}") String endpointOverride) {
        this.objectMapper = objectMapper;
        this.endpoint = endpointOverride == null || endpointOverride.isBlank()
                ? "http://127.0.0.1:" + serverPort + "/mcp"
                : endpointOverride;
    }

    @Override
    public McpSession open(String apiKey) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "iris-agent-demo", "version", "1.0"));
        HttpResponse<String> resp = post(null, "initialize", params, apiKey);
        JsonNode root = firstData(resp.body());
        if (root == null || root.has("error")) {
            throw new IllegalStateException("MCP initialize 失败: " + resp.body());
        }
        String session = resp.headers().firstValue("mcp-session-id")
                .orElseThrow(() -> new IllegalStateException("MCP initialize 响应缺 mcp-session-id"));
        // initialized 通知：应答可能为空体（202），忽略内容
        post(session, "notifications/initialized", null, apiKey);
        log.debug("MCP 会话已建立 endpoint={} session={}", endpoint, session);
        return new Session(session, apiKey == null ? "" : apiKey);
    }

    /** 一次 JSON-RPC POST。params 为 null 表示通知（无 id，无应答体要求）。 */
    private HttpResponse<String> post(String session, String method,
                                      Map<String, Object> params, String apiKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        boolean notification = method.startsWith("notifications/");
        if (!notification) {
            body.put("id", 1);
        }
        body.put("method", method);
        if (params != null) {
            body.put("params", params);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
        if (session != null) {
            builder.header("Mcp-Session-Id", session);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("X-API-Key", apiKey);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 从应答体取首个 data: JSON（SSE 形态）；纯 JSON 形态直接解析。
     * 返回 null 表示空应答（通知类）。
     */
    private JsonNode firstData(String body) throws Exception {
        if (body == null || body.isBlank()) {
            return null;
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            return objectMapper.readTree(trimmed);
        }
        for (String line : trimmed.split("\n")) {
            if (line.startsWith("data:")) {
                String payload = line.substring(5).trim();
                if (!payload.isEmpty() && payload.startsWith("{")) {
                    return objectMapper.readTree(payload);
                }
            }
        }
        return null;
    }

    /** 会话实现：单线程串行使用（编排层循环内逐个调用）。 */
    private final class Session implements McpSession {

        private final String sessionId;
        private final String apiKey;

        private Session(String sessionId, String apiKey) {
            this.sessionId = sessionId;
            this.apiKey = apiKey;
        }

        @Override
        public ListOfTools listTools() throws Exception {
            JsonNode root = firstData(post(sessionId, "tools/list", Map.of(), apiKey).body());
            JsonNode err = root == null ? null : root.get("error");
            if (err != null) {
                throw new IllegalStateException("MCP tools/list 失败: " + err);
            }
            List<ToolInfo> tools = new ArrayList<>();
            for (JsonNode t : root.path("result").path("tools")) {
                Map<String, Object> schema = objectMapper.convertValue(
                        t.path("inputSchema"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
                tools.add(new ToolInfo(t.path("name").asText(),
                        t.path("description").asText(""), schema));
            }
            return new ListOfTools(tools);
        }

        @Override
        public String callTool(String name, String argumentsJson) throws Exception {
            Map<String, Object> args = argumentsJson == null || argumentsJson.isBlank()
                    ? Map.of()
                    : objectMapper.readValue(argumentsJson,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                            });
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", name);
            params.put("arguments", args);
            JsonNode root = firstData(post(sessionId, "tools/call", params, apiKey).body());
            if (root == null) {
                return errorJson("MCP tools/call 空应答");
            }
            JsonNode err = root.get("error");
            if (err != null) {
                return errorJson(err.path("message").asText("MCP 调用失败"));
            }
            JsonNode result = root.path("result");
            StringBuilder text = new StringBuilder();
            for (JsonNode c : result.path("content")) {
                if ("text".equals(c.path("type").asText())) {
                    text.append(c.path("text").asText());
                }
            }
            String payload = text.toString();
            if (result.path("isError").asBoolean(false)) {
                // MCP 业务错误归一为 dispatcher 同款 {"error":true,...}，模型可自行修正
                return errorJson(payload.isBlank() ? "MCP 工具执行失败" : payload);
            }
            return payload.isBlank() ? "{}" : payload;
        }

        @Override
        public void close() {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(endpoint))
                        .timeout(Duration.ofSeconds(5))
                        .header("Mcp-Session-Id", sessionId)
                        .DELETE();
                if (!apiKey.isBlank()) {
                    builder.header("X-API-Key", apiKey);
                }
                http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                log.debug("MCP 会话关闭失败（忽略）session={} err={}", sessionId, e.getMessage(), e);
            }
        }

        private String errorJson(String message) {
            try {
                return objectMapper.writeValueAsString(Map.of("error", true, "message", message));
            } catch (Exception e) {
                return "{\"error\":true,\"message\":\"MCP 工具执行失败\"}";
            }
        }
    }
}

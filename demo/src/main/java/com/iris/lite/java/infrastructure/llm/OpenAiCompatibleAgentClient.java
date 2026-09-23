package com.iris.lite.java.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iris.lite.java.application.agent.AgentChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
 * OpenAI 兼容协议的 {@link AgentChatClient} 流式实现。
 *
 * <p><b>协议要点</b>：请求体加 {@code tools}（function calling）+ {@code stream: true}；
 * 响应是 SSE 流，逐行 {@code data: {...}}，增量在 {@code choices[0].delta} 里：
 * <ul>
 *   <li>{@code reasoning_content}：推理过程增量（DeepSeek 系推理模型特有，非答案）；</li>
 *   <li>{@code content}：正文增量；</li>
 *   <li>{@code tool_calls[]}：工具调用增量——同一调用的 {@code function.arguments}
 *       被<b>拆成多个 chunk 分段下发</b>，必须按 {@code index} 拼装，
 *       {@code id}/{@code name} 取首个非空片段；</li>
 *   <li>{@code finish_reason}：{@code tool_calls} / {@code stop} / {@code length}
 *       （length = 输出预算耗尽被截断，按失败处理）。</li>
 * </ul>
 *
 * <p>与 {@link OpenAiCompatibleLlmClient} 共用 {@code iris.llm.*} 配置；
 * 超时单独走 {@code iris.agent.timeout-seconds}（多轮工具 + 推理，远长于普通补全）。
 * 用 JDK HttpClient {@code ofLines()} 逐行读流，仍零第三方依赖。
 */
@Component
@ConditionalOnProperty(name = "iris.llm.enabled", havingValue = "true")
public class OpenAiCompatibleAgentClient implements AgentChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAgentClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;

    public OpenAiCompatibleAgentClient(
            @Value("${iris.llm.base-url}") String baseUrl,
            @Value("${iris.llm.api-key}") String apiKey,
            @Value("${iris.llm.model}") String model,
            @Value("${iris.llm.temperature:0.1}") double temperature,
            @Value("${iris.llm.max-tokens:8192}") int maxTokens,
            @Value("${iris.agent.timeout-seconds:120}") int timeoutSeconds,
            ObjectMapper objectMapper) {
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        this.apiKey = apiKey;
        this.model = model;
        // 推理模型红线：max-tokens 不足时 reasoning 耗尽预算、content 为空
        this.maxTokens = Math.max(maxTokens, 4096);
        this.temperature = temperature;
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("Agent 对话客户端已启用 endpoint={} model={} timeout={}s", endpoint, model, timeoutSeconds);
    }

    @Override
    public void chat(ChatRequest request, StreamListener listener) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(buildBody(request)))
                    .build();

            HttpResponse<java.util.stream.Stream<String>> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                // 流式失败时错误体不是 SSE，收集为文本片段便于定位。
                // Stream<String> 必须 close（ofLines 持有底层连接），否则每次
                // LLM 调用失败（429/5xx 常见）都泄漏一条连接
                try (java.util.stream.Stream<String> errorLines = response.body()) {
                    String body = errorLines.limit(20).reduce("", (a, b) -> a + b);
                    throw new IllegalStateException("Agent LLM 调用失败 HTTP " + response.statusCode()
                            + " body=" + snippet(body));
                }
            }
            accumulate(response.body(), listener);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent LLM 调用被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("Agent LLM 调用 IO 异常: " + e.getMessage(), e);
        }
    }

    /** 组装 OpenAI 兼容请求体：messages + tools + stream。 */
    private String buildBody(ChatRequest request) throws IOException {
        // 输出预算可按请求覆盖：推理模型红线 ≥4096（reasoning 耗尽预算则 content 为空），上限 32768 防失控
        int effectiveMaxTokens = request.maxTokens() == null
                ? maxTokens
                : Math.max(4096, Math.min(request.maxTokens(), 32768));
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        body.put("temperature", temperature);
        body.put("max_tokens", effectiveMaxTokens);
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage m : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", m.role());
            if (m.content() != null) {
                node.put("content", m.content());
            }
            if (!m.toolCalls().isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall c : m.toolCalls()) {
                    ObjectNode call = calls.addObject();
                    call.put("id", c.id());
                    call.put("type", "function");
                    call.putObject("function")
                            .put("name", c.name())
                            .put("arguments", c.argumentsJson() == null ? "{}" : c.argumentsJson());
                }
            }
            if (m.toolCallId() != null) {
                node.put("tool_call_id", m.toolCallId());
            }
        }
        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolSpec spec : request.tools()) {
                ObjectNode fn = tools.addObject()
                        .put("type", "function")
                        .putObject("function");
                fn.put("name", spec.name());
                fn.put("description", spec.description());
                fn.putPOJO("parameters", objectMapper.valueToTree(spec.parameters()));
            }
        }
        return objectMapper.writeValueAsString(body);
    }

    /**
     * 逐行消费 SSE 流并聚合增量。按 index 拼装 tool_calls 的 arguments 片段；
     * finish_reason 决定结束形态。流正常以 [DONE] 收尾，异常情况（连接中断）
     * 由 IOException/流提前结束兜底。
     */
    private void accumulate(java.util.stream.Stream<String> lines, StreamListener listener) {
        // 聚合中的工具调用：index -> [id, name, argumentsBuffer]
        Map<Integer, String[]> pendingCalls = new LinkedHashMap<>();
        StringBuilder content = new StringBuilder();
        String finishReason = null;

        try (java.util.stream.Stream<String> stream = lines) {
            for (String line : (Iterable<String>) stream::iterator) {
                if (line == null || line.isBlank() || line.startsWith(":")) {
                    continue; // SSE 注释/空行
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if ("[DONE]".equals(payload)) {
                    break;
                }
                JsonNode chunk;
                try {
                    chunk = objectMapper.readTree(payload);
                } catch (IOException e) {
                    log.debug("忽略不可解析的流 chunk: {}", snippet(payload));
                    continue;
                }
                JsonNode choice = chunk.path("choices").path(0);
                JsonNode delta = choice.path("delta");
                JsonNode reasoning = delta.path("reasoning_content");
                if (reasoning.isTextual() && !reasoning.asText().isEmpty()) {
                    listener.onReasoning(reasoning.asText());
                }
                JsonNode contentDelta = delta.path("content");
                if (contentDelta.isTextual() && !contentDelta.asText().isEmpty()) {
                    content.append(contentDelta.asText());
                    listener.onContent(contentDelta.asText());
                }
                JsonNode toolCallsDelta = delta.path("tool_calls");
                if (toolCallsDelta.isArray()) {
                    for (JsonNode tc : toolCallsDelta) {
                        int index = tc.path("index").asInt(0);
                        String[] slot = pendingCalls.computeIfAbsent(index,
                                k -> new String[]{null, null, ""});
                        JsonNode id = tc.path("id");
                        if (id.isTextual() && !id.asText().isBlank()) {
                            slot[0] = id.asText();
                        }
                        JsonNode name = tc.path("function").path("name");
                        if (name.isTextual() && !name.asText().isBlank()) {
                            slot[1] = name.asText();
                        }
                        JsonNode args = tc.path("function").path("arguments");
                        if (args.isTextual()) {
                            slot[2] = slot[2] + args.asText();
                        }
                    }
                }
                JsonNode finish = choice.path("finish_reason");
                if (finish.isTextual() && !finish.asText().isBlank()) {
                    finishReason = finish.asText();
                }
            }
        }

        if ("length".equals(finishReason)) {
            throw new IllegalStateException("LLM 输出被 max_tokens 截断（finish_reason=length），"
                    + "推理模型需更大输出预算");
        }
        if (!pendingCalls.isEmpty()) {
            List<ToolCall> calls = new ArrayList<>();
            for (String[] slot : pendingCalls.values()) {
                calls.add(new ToolCall(slot[0], slot[1],
                        slot[2] == null || slot[2].isBlank() ? "{}" : slot[2]));
            }
            listener.onFinished(FinishKind.TOOL_CALLS, content.toString(), List.copyOf(calls));
            return;
        }
        listener.onFinished(FinishKind.CONTENT, content.toString(), List.of());
    }

    /** 异常信息里只带响应前 200 字符，避免把整段响应灌进日志。 */
    private String snippet(String body) {
        if (body == null) {
            return "(null)";
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}

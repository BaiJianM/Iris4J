package com.iris.lite.java.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iris.lite.java.memory.LlmClient;
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

/**
 * OpenAI 兼容协议的 {@link LlmClient} 实现。
 *
 * <p><b>多供应商兼容的关键设计</b>：DeepSeek / OpenAI / 通义 / Kimi / Ollama
 * 等主流供应商与本地推理框架都提供 OpenAI 兼容的 {@code POST {base-url}/chat/completions}，
 * 请求/响应结构一致。因此本类不写死任何供应商，全部参数来自配置：
 * <pre>
 * iris:
 *   llm:
 *     enabled: true
 *     base-url: https://api.deepseek.com   # 换供应商只改这里
 *     model: deepseek-v4-flash
 *     api-key: ${IRIS_LLM_API_KEY:...}
 * </pre>
 *
 * <p><b>为什么用 JDK HttpClient 而非 Spring RestClient</b>：
 * 零新增依赖（infrastructure 不必引入 spring-web），且超时/重定向控制直接。
 *
 * <p><b>响应解析</b>：只取 {@code choices[0].message.content}。
 * 注意 DeepSeek v4 系列响应里还有 {@code reasoning_content}（思考过程）字段，
 * 那不是最终答案，绝不能取错字段。
 */
@Component
@ConditionalOnProperty(name = "iris.llm.enabled", havingValue = "true")
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;

    public OpenAiCompatibleLlmClient(
            @Value("${iris.llm.base-url}") String baseUrl,
            @Value("${iris.llm.api-key}") String apiKey,
            @Value("${iris.llm.model}") String model,
            @Value("${iris.llm.temperature:0.1}") double temperature,
            @Value("${iris.llm.max-tokens:1024}") int maxTokens,
            @Value("${iris.llm.timeout-seconds:60}") int timeoutSeconds,
            ObjectMapper objectMapper) {
        // 兼容 base-url 带不带尾斜杠两种写法；OpenAI 兼容路径固定为 /chat/completions
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/chat/completions";
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("LLM 客户端已启用 endpoint={} model={} timeout={}s", endpoint, model, timeoutSeconds);
    }

    /**
     * 阻塞式对话补全。失败抛 {@link IllegalStateException}（含 HTTP 状态与响应片段），
     * 由后台 worker 捕获降级，不向上传染。
     */
    @Override
    public String complete(String systemPrompt, String userPrompt) {
        try {
            // 组装 OpenAI 兼容请求体：messages 数组 + 采样参数
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long cost = System.currentTimeMillis() - start;

            if (response.statusCode() != 200) {
                throw new IllegalStateException("LLM 调用失败 HTTP " + response.statusCode()
                        + " body=" + snippet(response.body()));
            }
            // 只取 choices[0].message.content；reasoning_content 是思考过程不是答案
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                // 推理模型常见故障：max-tokens 太小，reasoning 阶段耗尽预算、content 为空。
                // 带上 finish_reason 便于直接定位截断问题
                String finish = root.path("choices").path(0).path("finish_reason").asText("(未知)");
                throw new IllegalStateException("LLM 响应缺少 content（finish_reason=" + finish
                        + "）: " + snippet(response.body()));
            }
            log.debug("LLM 补全完成 model={} 耗时={}ms 输出长度={}", model, cost, content.asText().length());
            return content.asText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM 调用被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("LLM 调用 IO 异常: " + e.getMessage(), e);
        }
    }

    /** 异常信息里只带响应前 200 字符，避免把整段响应灌进日志。 */
    private String snippet(String body) {
        if (body == null) {
            return "(null)";
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}

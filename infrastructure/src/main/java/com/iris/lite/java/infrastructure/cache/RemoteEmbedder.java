package com.iris.lite.java.infrastructure.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iris.lite.java.cache.embedder.Embedder;
import com.iris.lite.java.shared.metrics.IrisMetrics;
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
 * 远程 HTTP 向量化 Embedder（OpenAI 兼容 {@code POST /v1/embeddings}）。
 *
 * <p><b>模型与部署</b>：Qwen3-Embedding-0.6B，1024 维，多语言（C-MTEB 表现
 * 优于 bge-small/large 一代），部署在 Mac mini（M4/16GB）的 llama-server 上
 * （两个 llama-server 进程），Java 侧零模型推理。
 *
 * <p><b>启用方式</b>：{@code iris.embedder.type=remote}（与 bm25 二选一，
 * 条件装配互斥）。指纹 = modelId + 维度——切换后
 * fingerprintHash 变化，记忆/工作记忆/LLM 缓存索引按指纹段自动切新命名空间，
 * 语义缓存条目经 {@code SemanticReindexService} 重嵌入迁移，无需人工迁移数据。
 *
 * <p><b>失败语义（核心设计：零向量降级，绝不误命中）</b>：
 * 远程服务不可达/超时/返回异常时，本实现<b>不抛异常</b>，而是返回零向量并
 * 记 ERROR + 指标。零向量在本项目所有消费方语义一致：
 * <ul>
 *   <li>语义缓存：余弦恒 0 → 低于任何阈值 → 未命中 → 落回精确缓存/真实查询；</li>
 *   <li>LLM 缓存：{@code normSq==0} → 保守 MISS → 回真实 LLM 调用；</li>
 *   <li>记忆去重/检索：跳过相似度匹配，keyword 兜底路径不受影响。</li>
 * </ul>
 * 即语义加速层整体降级、结果正确性不受影响。这与「Embedder 缺失时
 * getIfAvailable() 返回 null 走旁路」的既有降级路径等价，只是退化发生在
 * 运行中而非启动时。<b>为什么不 fail-fast</b>：远程服务与 Java 应用生命周期
 * 独立（Mac mini 重启不应拖垮应用），带病启动的正确性问题已被零向量语义
 * 兜住；启动探测失败仅 WARN 指路。
 *
 * <p><b>线程安全</b>：{@link HttpClient} 与 {@link ObjectMapper} 均线程安全。
 */
@Component
@ConditionalOnProperty(name = "iris.embedder.type", havingValue = "remote")
public class RemoteEmbedder implements Embedder {

    private static final Logger log = LoggerFactory.getLogger(RemoteEmbedder.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String model;
    private final int timeoutMs;
    private final int dimension;
    private final String fingerprint;

    /** 服务健康态：连续失败进入 DOWN，恢复时记一次 UP（避免每次失败都刷同一条 ERROR）。 */
    private volatile boolean serviceUp = false;

    public RemoteEmbedder(
            @Value("${iris.embedder.remote.base-url}") String baseUrl,
            @Value("${iris.embedder.remote.model}") String model,
            @Value("${iris.embedder.remote.timeout-ms:5000}") int timeoutMs,
            @Value("${iris.semantic-cache.dimension:1024}") int dimension,
            @Value("${iris.semantic-cache.model-id:qwen3-embedding-0.6b}") String modelId,
            ObjectMapper objectMapper) {
        // OpenAI 兼容端点固定 /embeddings；base-url 需带 /v1 前缀（llama.cpp/Ollama 均是）
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/embeddings";
        this.model = model;
        this.timeoutMs = timeoutMs;
        this.dimension = dimension;
        this.fingerprint = modelId + "-d" + dimension;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        // 启动探测：成功则验证维度一致；失败仅 WARN 不阻断启动（理由见类注释失败语义）
        try {
            float[] probe = embed("启动自检");
            if (probe.length == 0) {
                // embed() 失败已记 ERROR；这里补指路即可
                log.warn("RemoteEmbedder 启动探测未通过（远程服务不可达？）：应用继续启动，"
                        + "语义层以零向量降级运行，服务恢复后自动转正常。endpoint={}", endpoint);
            } else {
                serviceUp = true;
                log.info("RemoteEmbedder 初始化 endpoint={} model={} dimension={} fingerprint={} 启动自检通过",
                        endpoint, model, probe.length, fingerprint);
            }
        } catch (Exception e) {
            log.warn("RemoteEmbedder 启动探测异常（不阻断启动，运行时按零向量降级）: {}", e.getMessage());
        }
    }

    /**
     * 文本向量化：OpenAI 兼容 {@code /v1/embeddings} → {@code data[0].embedding} → L2 归一化。
     *
     * <p>失败返回零向量（长度 = dimension，消费方据此跳过相似度匹配）；
     * 正常返回的向量已归一化（余弦 = 点积，接口契约）。
     */
    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[dimension];
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("input", text);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                String snippet = response.body() == null ? ""
                        : response.body().substring(0, Math.min(200, response.body().length()));
                throw new IOException("HTTP " + response.statusCode() + ": " + snippet);
            }
            JsonNode vec = objectMapper.readTree(response.body())
                    .path("data").path(0).path("embedding");
            if (!vec.isArray() || vec.isEmpty()) {
                throw new IOException("响应缺少 data[0].embedding");
            }
            if (vec.size() != dimension) {
                // 配置维度与模型实际输出不符：这是配置错误而非服务故障，必须炸出来
                throw new IllegalStateException("远程模型输出维度 " + vec.size()
                        + " 与配置 iris.semantic-cache.dimension=" + dimension + " 不一致，请核对配置");
            }
            float[] out = new float[dimension];
            double sq = 0;
            for (int i = 0; i < dimension; i++) {
                out[i] = (float) vec.get(i).asDouble();
                sq += (double) out[i] * out[i];
            }
            // L2 归一化（接口契约）：llama.cpp pooling=last 输出未归一化
            if (sq > 0) {
                float norm = (float) Math.sqrt(sq);
                for (int i = 0; i < dimension; i++) {
                    out[i] /= norm;
                }
            }
            markUp();
            return out;
        } catch (IllegalStateException e) {
            // 维度不一致属配置错误：不降级、直接抛，让它在第一次查询就暴露
            throw e;
        } catch (IOException | InterruptedException e) {
            markDown(e);
            return new float[dimension];
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }

    /** 指纹 = 模型标识 + 维度（如 {@code qwen3-embedding-0.6b-d1024}）。 */
    @Override
    public String fingerprint() {
        return fingerprint;
    }

    /** DOWN→UP 恢复时记一条 INFO（状态迁移日志，避免稳态刷屏）。 */
    private void markUp() {
        if (!serviceUp) {
            serviceUp = true;
            log.info("RemoteEmbedder 服务已恢复 endpoint={}", endpoint);
        }
    }

    /** UP→DOWN 进入降级态记 ERROR + 指标；稳态 DOWN 只记 WARN（限频由状态迁移天然保证）。 */
    private void markDown(Exception e) {
        if (serviceUp) {
            serviceUp = false;
            log.error("RemoteEmbedder 服务不可用，语义层降级为零向量（正确性不受影响，恢复后自动转正常）endpoint={} 原因={}",
                    endpoint, e.getMessage());
        } else {
            log.warn("RemoteEmbedder 仍不可用: {}", e.getMessage());
        }
        IrisMetrics.increment("iris.embedder.remote", "state", "down");
    }
}

package com.iris4j.infrastructure.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iris4j.application.rerank.CrossEncoderReranker;
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
import java.util.List;

/**
 * 远程 HTTP 交叉编码器重排（Cohere 格式 {@code POST /v1/rerank}）。
 *
 * <p><b>模型与部署</b>：Qwen3-Reranker-0.6B，生成式 yes/no 判定，多语言
 * （与 RemoteEmbedder 的 Qwen3-Embedding 同族配套），跑在 Mac mini
 * llama-server 上。项目外部署，Java 侧零模型推理。
 *
 * <p><b>⚠️ 模板前提</b>：Qwen3-Reranker 是 instruction 敏感模型——
 * 上游 GGUF 烘焙的 rerank 模板把 {@code <Instruct>} 固定写死为 web search 默认任务，
 * 对「是否同一个问题」类判定无区分度（同域句对一律 0.97+，判话题不判同题）。
 * 所用 GGUF 的模板 instruction 必须是任务定制中文指令
 * （「日期/数值/指标/方向完全一致才算相关」），此时正负完全可分
 * （正对 min 0.9950 / 进重排门槛的负对 max 0.6812）。**换 GGUF 必须确认模板含
 * 任务 instruction**。
 *
 * <p><b>启用方式</b>：{@code iris.llm-cache.rerank.enabled=true}。
 * 重排器必须项目外部署（llama-server /v1/rerank），Java 侧零模型推理。
 *
 * <p><b>分数标准（与接口契约对齐）</b>：llama.cpp 的 {@code relevance_score}
 * 对 Qwen3-Reranker 输出已是 sigmoid 后的 0-1 相关概率（相关 0.998 /
 * 无关 4e-5，分离干净），直接可用作跨模型阈值比较，无需二次映射。
 * 换 reranker 实现时若分数标准不同，必须先重标 {@code iris.llm-cache.rerank.threshold}。
 *
 * <p><b>失败语义（接口契约）</b>：score() 失败抛 {@link IllegalStateException}——
 * 调用方（LlmCacheService）按「重排不可用」保守处理（MISS → 回真实 LLM 调用）。
 * 静默通过等于放任微改写误命中，失败绝不能静默通过。
 *
 * <p><b>性能注</b>：接口支持逐句对 {@code score()} 与批量 {@code scoreBatch()}
 * （正向前向 1 次批量 + 反向逐对，2N 次往返压到 N+1 次）。调用方有召回线预筛
 * 与融合候选池上限（iris.rag.candidates），重排调用量可控。
 *
 * <p><b>线程安全</b>：HttpClient 与 ObjectMapper 均线程安全。
 */
@Component
@ConditionalOnProperty(name = "iris.llm-cache.rerank.enabled", havingValue = "true")
public class RemoteCrossEncoderReranker implements CrossEncoderReranker {

    private static final Logger log = LoggerFactory.getLogger(RemoteCrossEncoderReranker.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String endpoint;
    private final String model;
    private final int timeoutMs;
    private final boolean twoSided;
    private final String fingerprint;

    public RemoteCrossEncoderReranker(
            @Value("${iris.llm-cache.rerank.base-url}") String baseUrl,
            @Value("${iris.llm-cache.rerank.model}") String model,
            @Value("${iris.llm-cache.rerank.timeout-ms:10000}") int timeoutMs,
            @Value("${iris.llm-cache.rerank.two-sided:true}") boolean twoSided,
            ObjectMapper objectMapper) {
        // Cohere/OpenAI 兼容端点固定 /rerank；base-url 需带 /v1 前缀
        this.endpoint = baseUrl.replaceAll("/+$", "") + "/rerank";
        this.model = model;
        this.timeoutMs = timeoutMs;
        this.twoSided = twoSided;
        this.fingerprint = model;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        // 启动自检：真实句对推理，输出必须是有界的 0-1 分数——服务不可达或
        // GGUF 是坏转换（分数恒近零，llama.cpp #16407 坑）都在启动阶段暴露
        double probe = score("启动自检查询", "启动自检：这是一段与查询直接相关的候选文本");
        if (!Double.isFinite(probe) || probe < 0 || probe > 1) {
            throw new IllegalStateException("RemoteReranker 自检分数超出 0-1: " + probe
                    + "，请核对模型与端点（" + endpoint + "）");
        }
        log.info("RemoteCrossEncoderReranker 初始化 endpoint={} model={} 自检分数={}",
                endpoint, model, String.format("%.4f", probe));
    }

    /**
     * 句对相关性分数：/v1/rerank 调用 → results[0].relevance_score（0-1）。
     *
     * <p><b>双向取 min（two-sided=true，缺省）</b>：Qwen3-Reranker 分数有方向性
     * （「订单量→销售额」0.9005 vs 反向 0.6812——query 视角下相关指标都算
     * 半相关）。生产中 (新 prompt, 存量 prompt) 两个方向都会出现（取决于哪条先
     * 入库），单方向阈值在 0.90 处贴边；双向取 min 后该负对最高分 0.6812，
     * 与正对 min 0.9929 完全可分，阈值鲁棒。代价：重排候选 ×2 次调用
     * （~300ms/对），候选已被召回线预筛，量小可接受。
     *
     * @throws IllegalStateException 服务不可达/超时/响应异常（调用方保守 MISS）
     */
    @Override
    public double score(String query, String text) {
        if (query == null || query.isBlank() || text == null || text.isBlank()) {
            return 0;
        }
        double forward = callRerank(query.trim(), text.trim());
        if (!twoSided) {
            return forward;
        }
        double backward = callRerank(text.trim(), query.trim());
        return Math.min(forward, backward);
    }

    /** 单方向 /v1/rerank 调用。 */
    private double callRerank(String query, String doc) {
        return callRerankBatch(query, List.of(doc))[0];
    }

    /**
     * 批量重排（RAG 融合候选池）：正向前向 1 次批量调用，反向逐对补 N 次后取 min。
     *
     * <p>反向无法批量——每个句对的「query 角色互换」后 query 各不相同，
     * /v1/rerank 单请求只接受一个 query。批量把 2N 次往返压到 N+1 次
     * （12 候选：24 次 → 13 次，LAN 下省 ~1.6s）。
     *
     * <p>失败语义与 {@link #score} 一致：整体抛 {@link IllegalStateException}，
     * 调用方保守降级（不部分采信）。
     */
    @Override
    public double[] scoreBatch(String query, List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new double[0];
        }
        if (query == null || query.isBlank()) {
            return new double[texts.size()];
        }
        // blank 候选不打远程：按 0 分填（与 score() 契约一致），其余批量送出
        List<Integer> valid = new ArrayList<>();
        List<String> docs = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            docs.add(t == null ? "" : t.trim());
            if (t != null && !t.isBlank()) {
                valid.add(i);
            }
        }
        double[] forward = new double[texts.size()];
        if (!valid.isEmpty()) {
            double[] scores = callRerankBatch(query.trim(),
                    valid.stream().map(docs::get).toList());
            for (int i = 0; i < valid.size(); i++) {
                forward[valid.get(i)] = scores[i];
            }
        }
        if (!twoSided) {
            return forward;
        }
        for (int idx : valid) {
            double backward = callRerankBatch(docs.get(idx), List.of(query.trim()))[0];
            forward[idx] = Math.min(forward[idx], backward);
        }
        return forward;
    }

    /** 单方向 /v1/rerank 批量调用：documents 数组一次送 N 条，按 results[].index 对位取分。 */
    private double[] callRerankBatch(String query, List<String> docs) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("query", query);
            ArrayNode arr = body.putArray("documents");
            docs.forEach(arr::add);
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
            JsonNode results = objectMapper.readTree(response.body()).path("results");
            if (!results.isArray() || results.size() != docs.size()) {
                throw new IOException("响应 results 数量不符: " + results.size() + "/" + docs.size());
            }
            double[] out = new double[docs.size()];
            for (int i = 0; i < results.size(); i++) {
                JsonNode r = results.get(i);
                // results[].index 是请求 documents 的下标（Cohere 契约）；缺失时按数组序兜底
                int pos = r.hasNonNull("index") ? r.get("index").asInt() : i;
                JsonNode scoreNode = r.path("relevance_score");
                if (!scoreNode.isNumber()) {
                    throw new IOException("响应缺少 results[" + i + "].relevance_score");
                }
                out[pos] = scoreNode.asDouble();
            }
            return out;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("远程 rerank 调用失败（调用方应按重排不可用保守处理）: "
                    + e.getMessage(), e);
        }
    }

    @Override
    public String fingerprint() {
        return fingerprint;
    }
}

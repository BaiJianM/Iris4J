package com.iris.lite.java.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.lite.java.api.security.AgentIdentity;
import com.iris.lite.java.application.query.RecipeDraftStore;
import com.iris.lite.java.application.agent.AgentChatClient;
import com.iris.lite.java.application.agent.AgentEventSink;
import com.iris.lite.java.application.agent.AgentService;
import com.iris.lite.java.application.agent.AgentToolCatalog;
import com.iris.lite.java.application.agent.McpToolPort;
import com.iris.lite.java.shared.error.ErrorCode;
import com.iris.lite.java.shared.error.IrisException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Agent 演示 REST 入口：工具目录查询 + SSE 流式问答。
 *
 * <p><b>SSE 传输</b>：POST 返回 {@link SseEmitter}（EventSource 不支持 POST 与
 * 自定义鉴权头，前端用 fetch + ReadableStream 消费）。事件协议：
 * round / reasoning / tool_call / tool_result / cache / answer_delta /
 * answer_reset / done / error；LLM 推理静默期由心跳线程发 SSE 注释行
 * {@code : ping} 防中间层断连。所有发送在 emitter 对象上同步串行，
 * 避免并发写坏流。
 *
 * <p><b>身份</b>：与 REST/MCP 同一 X-API-Key 入口（ApiKeyAuthFilter 写入
 * request attribute）；在起异步前于请求线程上解析（异步后 attribute 不可见）。
 * legacy 全可见身份用完整工具目录；受限 agent 用查询/记忆子集。
 *
 * <p><b>执行线程</b>：JDK 21 虚拟线程跑循环——阻塞的 LLM 流式读不占平台线程，
 * 并发演示会话无线程池压力。
 */
@RestController
@RequestMapping("/api/v1/agent")
@ConditionalOnProperty(name = "iris.agent.enabled", havingValue = "true", matchIfMissing = true)
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /** 心跳间隔（秒）：DeepSeek 推理阶段可能长时间无增量。 */
    private static final int HEARTBEAT_SECONDS = 15;

    /**
     * 全局共享心跳调度池：心跳任务极轻（每 15s 一条注释行），每会话单建
     * ScheduledExecutorService 会各占一条平台线程且线程同名难排查——
     * 池化后任务级隔离，emitter 完成时 cancel 对应 ScheduledFuture。
     */
    private static final ScheduledExecutorService HEARTBEAT_POOL =
            new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "agent-heartbeat");
                t.setDaemon(true);
                return t;
            });

    /** 指标暴露：ExecutorMetricsConfig 据此注册 Micrometer gauge（池本身仍私有）。 */
    public static ScheduledExecutorService heartbeatPool() {
        return HEARTBEAT_POOL;
    }

    /** 停机钩子：常驻池随容器关闭收口，不再接受新任务（在跑的心跳任务极短，无需等待）。 */
    @PreDestroy
    public void shutdownHeartbeatPool() {
        HEARTBEAT_POOL.shutdown();
    }

    private final AgentService agentService;
    private final ObjectMapper objectMapper;
    private final org.springframework.beans.factory.ObjectProvider<McpToolPort> mcpToolPortProvider;
    private final org.springframework.beans.factory.ObjectProvider<com.iris.lite.java.application.query.RecipeDraftStore> recipeDraftStoreProvider;
    private final String defaultNamespace;
    // 演示页头部展示用：当前生效的 LLM 配置（真读配置，前端不硬编码）
    private final boolean llmEnabled;
    private final String llmModel;
    private final double llmTemperature;
    private final int llmMaxTokens;
    private final String llmBaseUrl;
    private final int maxIterations;
    private final int agentTimeoutSeconds;

    public AgentController(AgentService agentService,
                           ObjectMapper objectMapper,
                           org.springframework.beans.factory.ObjectProvider<McpToolPort> mcpToolPortProvider,
                           org.springframework.beans.factory.ObjectProvider<com.iris.lite.java.application.query.RecipeDraftStore> recipeDraftStoreProvider,
                           @Value("${iris.namespace:ecomm}") String defaultNamespace,
                           @Value("${iris.llm.enabled:true}") boolean llmEnabled,
                           @Value("${iris.llm.model:}") String llmModel,
                           @Value("${iris.llm.temperature:0.1}") double llmTemperature,
                           @Value("${iris.llm.max-tokens:8192}") int llmMaxTokens,
                           @Value("${iris.llm.base-url:}") String llmBaseUrl,
                           @Value("${iris.agent.max-iterations:6}") int maxIterations,
                           @Value("${iris.agent.timeout-seconds:120}") int agentTimeoutSeconds) {
        this.agentService = agentService;
        this.objectMapper = objectMapper;
        this.mcpToolPortProvider = mcpToolPortProvider;
        this.recipeDraftStoreProvider = recipeDraftStoreProvider;
        this.defaultNamespace = defaultNamespace;
        this.llmEnabled = llmEnabled;
        this.llmModel = llmModel;
        this.llmTemperature = llmTemperature;
        this.llmMaxTokens = llmMaxTokens;
        this.llmBaseUrl = llmBaseUrl;
        this.maxIterations = maxIterations;
        this.agentTimeoutSeconds = agentTimeoutSeconds;
    }

    /**
     * 当前生效的 Agent/LLM 配置（演示页头部展示「正在用哪个模型、什么温度」）。
     *
     * <p>读的是服务端真实配置而非前端硬编码——演示页要能一眼看出改配置后是否生效。
     * <b>不返回 api-key</b>，base-url 也只取主机名（避免泄露内网路径/密钥片段）。
     */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", llmEnabled);
        out.put("model", llmModel == null || llmModel.isBlank() ? "未配置" : llmModel);
        out.put("temperature", llmTemperature);
        out.put("maxTokens", llmMaxTokens);
        out.put("provider", hostOf(llmBaseUrl));
        out.put("maxIterations", maxIterations);
        out.put("agentTimeoutSeconds", agentTimeoutSeconds);
        // 系统提示词静态部分指纹（版本化资产核对用）
        AgentService.PromptDigest digest = agentService.promptDigest();
        out.put("systemPromptSha256", digest.sha256());
        out.put("systemPromptChars", digest.chars());
        return out;
    }

    /** 从 base-url 取主机名；解析失败或未配置时返回空串（前端降级不显示提供商）。 */
    private static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            // base-url 仅用于展示字段，解析失败降级为空串（展示空 ≠ 错误页）
            log.debug("base-url 解析失败，按空串降级: {}", e.getMessage());
            return "";
        }
    }

    /** 当前身份可用的工具目录（Agent 看到的工具面 = 演示页展示的工具面）。mcp=true 返回 MCP tools/list 实时目录。 */
    @GetMapping("/tools")
    public Map<String, Object> tools(jakarta.servlet.http.HttpServletRequest request,
                                     @org.springframework.web.bind.annotation.RequestParam(
                                             name = "mcp", required = false, defaultValue = "false") boolean mcp) {
        String apiKey = request.getHeader(AgentIdentity.API_KEY_HEADER);
        if (mcp) {
            McpToolPort port = mcpToolPortProvider.getIfAvailable();
            if (port == null) {
                throw new IrisException(ErrorCode.INVALID_QUERY, "MCP 客户端不可用");
            }
            try (McpToolPort.McpSession session = port.open(apiKey)) {
                return Map.of("tools", session.listTools().tools());
            } catch (Exception e) {
                throw new IrisException(ErrorCode.INVALID_QUERY, "MCP 目录获取失败: " + e.getMessage());
            }
        }
        List<AgentChatClient.ToolSpec> catalog = identity(request).hasTag(AgentIdentity.WILDCARD_TAG)
                ? AgentToolCatalog.all() : AgentToolCatalog.restricted();
        return Map.of("tools", catalog);
    }

    /**
     * 配方草稿审核视图（自进化循环）。
     *
     * <p>返回最近的草稿样本（顺畅会话的工具序列 = 可固化的配方候选；
     * 失败会话 = 原语/配方缺口的证据）。人工审核后把顺畅样本整理成 recipe 条目
     * 合入 {@code {ns}.graph.yml}（mtime 热载即刻生效）。只读端点，不提供
     * 自动合入——LLM 生成配方会编造工具名，审核必须人工。
     */
    @GetMapping("/recipe-drafts")
    public Map<String, Object> recipeDrafts(
            @org.springframework.web.bind.annotation.RequestParam(
                    name = "namespace", required = false) String namespace,
            @org.springframework.web.bind.annotation.RequestParam(
                    name = "limit", required = false, defaultValue = "50") int limit) {
        RecipeDraftStore store = recipeDraftStoreProvider.getIfAvailable();
        if (store == null) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "配方草稿池未装配（recipe-drafts 未启用）");
        }
        String ns = namespace == null || namespace.isBlank() ? defaultNamespace : namespace;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("namespace", ns);
        out.put("total", store.size(ns));
        out.put("drafts", store.list(ns, Math.max(1, Math.min(limit, 200))));
        return out;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody AgentChatRequest body,
                           jakarta.servlet.http.HttpServletRequest request) {
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "message 不能为空");
        }
        // 身份与命名空间在起异步前解析（异步边界后 request attribute 不可见）
        AgentIdentity identity = identity(request);
        // apiKey 同理：异步后 request 可能被容器回收，头信息必须在请求线程上读完
        String apiKey = request.getHeader(AgentIdentity.API_KEY_HEADER);
        String namespace = body.namespace() == null || body.namespace().isBlank()
                ? defaultNamespace : body.namespace();

        SseEmitter emitter = new SseEmitter(0L); // 超时由 LLM 客户端与循环护栏控制
        heartbeats(emitter);

        Thread.ofVirtual().name("agent-run-").start(() -> {
            try {
                agentService.run(
                        new AgentService.AgentRunRequest(namespace,
                                body.sessionId() == null || body.sessionId().isBlank()
                                        ? "agent-demo" : body.sessionId(),
                                body.message(),
                                Boolean.TRUE.equals(body.bypassCache()),
                                identity.tags(),
                                body.maxTurns(),
                                body.maxTokens(),
                                Boolean.TRUE.equals(body.mcpTools()),
                                apiKey),
                        sseSink(emitter));
                emitter.complete();
            } catch (Exception e) {
                // 客户端断开（send 失败）与真实异常都集中到这里
                log.info("Agent 运行结束（异常或客户端断开）: {}", e.getMessage(), e);
                try {
                    // 与 sseSink/心跳同一锁纪律：所有 send 在 emitter 上同步串行
                    synchronized (emitter) {
                        emitter.send(SseEmitter.event().name("error")
                                .data(json(Map.of("message", safeMessage(e))), MediaType.APPLICATION_JSON));
                    }
                } catch (Exception ignored) {
                    // 连接已不可用，静默
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** SSE 事件出口适配器：application 事件 → SSE 帧；发送失败向上抛终止循环。 */
    private AgentEventSink sseSink(SseEmitter emitter) {
        return (event, data) -> {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().name(event)
                            .data(json(data), MediaType.APPLICATION_JSON));
                }
            } catch (Exception e) {
                throw new IllegalStateException("SSE 发送失败（客户端可能已断开）", e);
            }
        };
    }

    /** 心跳守护：共享池定时发 SSE 注释行；连接关闭后 send 抛错即取消任务。 */
    private void heartbeats(SseEmitter emitter) {
        ScheduledFuture<?> task = HEARTBEAT_POOL.scheduleAtFixedRate(() -> {
            try {
                synchronized (emitter) {
                    emitter.send(SseEmitter.event().comment("ping"));
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        Runnable cancel = () -> task.cancel(false);
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(e -> cancel.run());
    }

    /** 鉴权过滤器写入的身份；无（鉴权关闭）按 legacy 全可见处理。 */
    private AgentIdentity identity(jakarta.servlet.http.HttpServletRequest request) {
        Object attr = request.getAttribute(AgentIdentity.REQUEST_ATTRIBUTE);
        return attr instanceof AgentIdentity a ? a : AgentIdentity.legacy();
    }

    private String json(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            // 心跳注释行的序列化失败只降级为空 JSON，绝不能让心跳打断 SSE
            log.debug("心跳 JSON 序列化失败，按空对象降级", e);
            return "{}";
        }
    }

    private String safeMessage(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 300 ? m.substring(0, 300) : m;
    }

    /** 问答请求体。sessionId 缺省用 "agent-demo"；maxTurns/maxTokens 缺省用服务端配置（6 / iris.llm.max-tokens）；mcpTools=true 走 MCP 按需发现链路。 */
    public record AgentChatRequest(String sessionId, String message,
                                   String namespace, Boolean bypassCache, Integer maxTurns,
                                   Integer maxTokens, Boolean mcpTools) {
    }
}

package com.iris.lite.application.agent;

import com.iris.lite.application.schema.SchemaFileWriter;
import java.util.List;
import java.util.Map;

/**
 * Agent 对话客户端端口：带工具调用 + 流式输出的 LLM 对话。
 *
 * <p><b>与 {@code memory.LlmClient} 的分工</b>：LlmClient 服务于记忆抽取等
 * 后台任务，只要一段补全文本；本端口服务于 Agent 循环——模型可以决定调用
 * 工具（OpenAI function calling 协议），且推理/正文增量通过监听器实时外抛，
 * 供 SSE 层逐字转发给浏览器。
 *
 * <p><b>端口放 application 的原因</b>：Agent 循环编排（本模块）只依赖本抽象；
 * 协议细节（OpenAI 兼容流式 chunk 解析）封装在 infrastructure 实现——
 * 与 {@code memory.LlmClient} / {@code SchemaFileWriter} 同一端口模式。
 * application 不出现任何 HTTP/JSON 协议细节（参数 schema 的构造除外）。
 */
public interface AgentChatClient {

    /**
     * 执行一轮流式对话补全（阻塞到当前轮结束，增量经监听器实时外抛）。
     *
     * @param request  消息序列 + 可用工具目录
     * @param listener 流式监听器（推理/正文增量、完成事件）
     * @throws IllegalStateException 供应商调用失败（含 HTTP 状态与响应片段）
     */
    void chat(ChatRequest request, StreamListener listener);

    /** 工具声明：OpenAI function calling 的 function 结构（parameters 为 JSON Schema）。 */
    record ToolSpec(String name, String description, Map<String, Object> parameters) {
    }

    /** 模型发起的一次工具调用（参数为 JSON 文本，由调用方解析执行）。 */
    record ToolCall(String id, String name, String argumentsJson) {
    }

    /**
     * 对话消息。role ∈ system/user/assistant/tool：
     * <ul>
     *   <li>assistant 携带 toolCalls = 模型上一轮发起的工具调用；</li>
     *   <li>tool 携带 toolCallId + content = 该调用的执行结果（JSON 文本）。</li>
     * </ul>
     */
    record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        public ChatMessage {
            if (toolCalls == null) {
                toolCalls = List.of();
            }
        }

        public static ChatMessage of(String role, String content) {
            return new ChatMessage(role, content, null, null);
        }

        public static ChatMessage toolResult(String toolCallId, String content) {
            return new ChatMessage("tool", content, null, toolCallId);
        }
    }

    /**
     * 单轮对话请求。maxTokens 为当前运行的输出预算覆盖（null = 用 iris.llm.max-tokens
     * 配置；实现侧须 clamp，推理模型红线 ≥4096）。
     */
    record ChatRequest(List<ChatMessage> messages, List<ToolSpec> tools, Integer maxTokens) {
        public ChatRequest(List<ChatMessage> messages, List<ToolSpec> tools) {
            this(messages, tools, null);
        }
    }

    /** 当前轮结束形态：CONTENT = 生成正文（终答或中间叙述）；TOOL_CALLS = 要求执行工具。 */
    enum FinishKind {
        CONTENT,
        TOOL_CALLS
    }

    /**
     * 流式监听器。增量回调发生在 chat() 调用线程上（SSE 发送方同线程，
     * 无并发问题）；onFinished 保证恰好回调一次，chat() 随后返回。
     */
    interface StreamListener {

        /** 推理过程增量（DeepSeek reasoning_content；非答案，仅用于「思考中」展示）。 */
        default void onReasoning(String delta) {
        }

        /** 正文增量（最终答案的逐字流）。 */
        default void onContent(String delta) {
        }

        /**
         * 当前轮结束。kind=CONTENT 时 content 为完整正文、toolCalls 为空；
         * kind=TOOL_CALLS 时 toolCalls 为模型发起的全部调用（按流中顺序）。
         */
        void onFinished(FinishKind kind, String content, List<ToolCall> toolCalls);
    }
}

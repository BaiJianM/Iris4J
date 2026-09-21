package com.iris.lite.application.agent;

import java.util.List;

/**
 * Agent 运行服务：工具调用循环的编排入口。
 *
 * <p>一次 {@link #run} = 一次完整问答：语义缓存查找 → LLM 工具
 * 调用循环（≤max-iterations 轮）→ 终答缓存回存（带数据版本依赖）→
 * 会话工作记忆留痕。过程事件经 {@link AgentEventSink} 实时外抛。
 */
public interface AgentService {

    /**
     * 执行一次 Agent 问答（阻塞到完成或失败；流式增量经 sink 外抛）。
     *
     * @param request 运行请求
     * @param sink    事件出口
     */
    void run(AgentRunRequest request, AgentEventSink sink);

    /**
     * 系统提示词<b>静态部分</b>的指纹（SYSTEM_PROMPT + MCP_MODE_PROMPT）。
     *
     * <p>刻意不含动态注入的 Schema 摘要与时间锚点——后者随时间/表结构变化，
     * 纳入哈希会使指纹无法用于版本核对。提示词是需版本化的资产，指纹暴露到
     * /api/v1/agent/info 供人工核对线上生效版本。
     */
    PromptDigest promptDigest();

    /** 提示词指纹：静态部分 SHA-256 前 16 位 + 字符数。 */
    record PromptDigest(String sha256, int chars) {
    }

    /**
     * @param namespace   命名空间（数据/记忆/缓存隔离边界）
     * @param sessionId   会话 id（工作记忆绑定；同一会话可连续追问）
     * @param message     用户消息
     * @param bypassCache 实时敏感场景 true = 跳过语义缓存
     * @param agentTags   访问 tags（鉴权层注入，编排层透传给查询链，不可伪造）
     * @param maxTurns    当前运行的最大推理轮数（null = 用 iris.agent.max-iterations 默认值；
     *                    演示页可动态调整，服务端 clamp 到 [1,20] 防失控）
     * @param maxTokens   当前运行的单轮输出预算 max_tokens（null = 用 iris.llm.max-tokens 默认值；
     *                    演示页可动态调整，服务端 clamp 到 [4096,32768]，推理模型红线 ≥4096）
     * @param mcpTools    true = 以真实 MCP 客户端身份接入 /mcp（工具面 = tools/list 25 个，
     *                    执行走 tools/call，完整触发按需发现两跳）；false = 直连内置目录
     * @param apiKey      调用方 API key（mcpTools 模式透传给 /mcp 鉴权；直连模式忽略）
     */
    record AgentRunRequest(String namespace, String sessionId, String message,
                           boolean bypassCache, List<String> agentTags, Integer maxTurns,
                           Integer maxTokens, boolean mcpTools, String apiKey) {
    }
}

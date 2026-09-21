package com.iris.lite.memory;

/**
 * LLM 对话补全端口（T5 自动记忆抽取引入）。
 *
 * <p><b>端口在 memory 模块、实现在 infrastructure</b>：与 {@code Embedder}
 * （接口在 cache 模块、实现在 infrastructure）同一套路——业务模块只声明
 * "我需要什么"，外部系统怎么调由适配器隔离，供应商差异不得泄漏到业务层。
 *
 * <p><b>多供应商兼容</b>：实现类走 OpenAI 兼容协议（/chat/completions），
 * DeepSeek / OpenAI / 通义 / Kimi 等兼容供应商只需换配置
 * （base-url + model + api-key），代码零改动。
 *
 * <p><b>调用方注意</b>：本接口是阻塞调用（LLM 推理秒级耗时），
 * <b>禁止在请求线程 / 查询链路上直接调用</b>——抽取类场景必须在
 * 独立的后台 worker 线程池里执行（见 {@code MemoryExtractionService}）。
 */
public interface LlmClient {

    /**
     * 一次对话补全：给定系统提示词与用户提示词，返回模型输出文本。
     *
     * @param systemPrompt 系统提示词（角色/输出格式约束）
     * @param userPrompt   用户提示词（当前调用的输入内容）
     * @return 模型输出的文本；调用失败抛运行时异常（由调用方决定降级策略）
     */
    String complete(String systemPrompt, String userPrompt);
}

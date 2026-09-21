package com.iris.lite.application.agent;

import java.util.Map;

/**
 * Agent 运行事件出口：编排层把循环过程以结构化事件外抛。
 *
 * <p><b>application 无 Web 依赖</b>：本端口不知道 SSE 的存在；api 层用
 * SseEmitter 适配器实现它，把事件翻译为 SSE 帧（round/reasoning/tool_call/
 * tool_result/cache/answer_delta/answer_reset/done/error）逐条推给浏览器。
 *
 * <p><b>背压语义</b>：emit 在编排线程上同步执行（SSE 发送即 flush）；
 * 发送失败（客户端断开）时实现应抛异常——编排层捕获后安静终止，
 * 不产生半截状态。
 */
public interface AgentEventSink {

    /**
     * 发送一个事件。
     *
     * @param event 事件类型（SSE event name）
     * @param data  事件负载（序列化由实现层负责）
     */
    void emit(String event, Map<String, Object> data);
}

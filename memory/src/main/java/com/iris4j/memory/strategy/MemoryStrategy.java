package com.iris4j.memory.strategy;

import java.util.List;

/**
 * 记忆抽取策略接口。
 *
 * <p><b>策略语义</b>：决定"从一段会话文本里抽出什么样的长期记忆"——
 * 内置 discrete（离散事实）/ summary（会话摘要）/ preferences（用户偏好）/
 * custom（自定义 prompt）四种；本接口是它们在 Java 侧的统一抽象。
 *
 * <p><b>选择机制</b>：由调用方/配置显式指定（非 LLM 自动路由），
 * 会话级配置优先于全局缺省，见 {@link MemoryStrategyRegistry}。
 *
 * <p><b>实现约束</b>：实现类只负责"给 LLM 什么 prompt、把输出解析成什么候选"，
 * LLM 调用与 JSON 容错解析由执行器统一承担（{@code LlmStrategyExecutor}），
 * 策略实现不直接持有 LLM 客户端。LLM 调用失败时实现可能抛出异常
 * （由协调器统一重试/降级），解析失败归约为空列表。
 */
public interface MemoryStrategy {

    /** 策略名（注册表键）：discrete / summary / preferences / custom。 */
    String name();

    /**
     * 从一段会话文本中抽取候选记忆。
     *
     * @param sessionText 会话工作记忆拼接文本
     * @param context     上下文（当前时间/会话标识/策略配置）
     * @return 候选记忆列表；没有可抽取内容返回空列表，不应返回 null
     */
    List<Candidate> extract(String sessionText, StrategyContext context);
}

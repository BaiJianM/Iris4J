package com.iris.lite.memory.strategy;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 策略执行的上下文参数：策略实现从这里取"当前时间/会话标识/自定义配置"，
 * 不与任何 Spring 或 Redis 类型耦合（memory 模块保持纯模型）。
 *
 * @param namespace       命名空间
 * @param sessionId       会话 ID
 * @param currentDateTime 当前时间的人类可读形态（供 prompt 注入，相对时间锚定的基准）
 * @param customPrompt    custom 策略的用户自定义 prompt；其他策略忽略
 * @param topKTopics      discrete 策略的主题标签数量上限（settings.top_k_topics）
 */
public record StrategyContext(
        String namespace,
        String sessionId,
        String currentDateTime,
        String customPrompt,
        int topKTopics) {

    /** 当前时间默认格式（注入 prompt 的语义：星期+日期+时间）。 */
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd EEEE HH:mm zzz");

    /** 紧凑构造器：topKTopics 非法时回落 5（缺省量级）。 */
    public StrategyContext {
        if (topKTopics <= 0) {
            topKTopics = 5;
        }
    }

    /** 从当前时刻构造上下文（应用层装配入口）。 */
    public static StrategyContext of(String namespace, String sessionId, String customPrompt, int topKTopics) {
        return new StrategyContext(
                namespace, sessionId,
                ZonedDateTime.now().format(FORMATTER),
                customPrompt, topKTopics);
    }
}

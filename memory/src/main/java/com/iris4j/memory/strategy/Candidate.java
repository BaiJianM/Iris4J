package com.iris4j.memory.strategy;

import com.iris4j.memory.MemoryType;

import java.util.List;

/**
 * 记忆策略抽取出的候选记忆，统一输出结构
 * {@code {type, text, topics, entities}}（外加 episodic 专属的 eventDate）。
 *
 * @param type      记忆类型（episodic 必带 eventDate；summary/preferences 恒 semantic）
 * @param text      记忆文本（第三人称陈述、上下文已锚定）
 * @param topics    主题标签（discrete 策略产出，top-k）
 * @param entities  涉及的实体/人名列表
 * @param eventDate episodic 事件发生的绝对日期（ISO 8601）；semantic 为 null
 */
public record Candidate(
        MemoryType type,
        String text,
        List<String> topics,
        List<String> entities,
        String eventDate) {

    /** 紧凑构造器：type 必填，列表字段兜底为空列表（策略实现无需处处判 null）。 */
    public Candidate {
        if (type == null) {
            throw new IllegalArgumentException("候选记忆 type 不能为空");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("候选记忆 text 不能为空");
        }
        topics = topics == null ? List.of() : List.copyOf(topics);
        entities = entities == null ? List.of() : List.copyOf(entities);
    }

    /** semantic 快捷工厂（无 eventDate）。 */
    public static Candidate semantic(String text, List<String> topics, List<String> entities) {
        return new Candidate(MemoryType.SEMANTIC, text, topics, entities, null);
    }
}

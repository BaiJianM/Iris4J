package com.iris.lite.memory;

/**
 * 记忆类型枚举。
 *
 * <ul>
 *   <li><b>EPISODIC</b>（情景记忆）：发生在特定时间点的事件，必带 eventDate；</li>
 *   <li><b>SEMANTIC</b>（语义记忆）：用户偏好、事实、规则等与时间无关的知识；</li>
 *   <li><b>MESSAGE</b>：工作记忆消息的默认类型——本项目工作记忆
 *       是独立的 {@code WorkingMemoryEntry}，本枚举值仅为保持枚举完备而保留，不落库。</li>
 * </ul>
 */
public enum MemoryType {
    EPISODIC,
    SEMANTIC,
    MESSAGE
}

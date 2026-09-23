package com.iris.lite.java.memory;

import java.util.List;

/**
 * 工作记忆会话文档：一个 (namespace, sessionId) 对应的完整存储形态。
 *
 * <p><b>为什么是"JSON 对象"而不是"JSON 数组"</b>：
 * 除 messages 外还需存放 context（自动摘要的旧消息内容）与
 * 抽取策略配置——会话渐进摘要需要一个地方存放滚动摘要，策略配置需要会话级覆盖，
 * 两者都装不进纯数组，故整体文档化为 {@code {context, strategy, entries[]}}。
 *
 * <p><b>owner（ownerId 层）</b>：文档级归属，写入时随首条带 owner 的条目确定
 * （整个会话属于同一个 owner），异步抽取长期记忆时从文档继承。null/blank
 * 归一化为 "default"；旧版本 JSON 无该字段时同样兜底。
 *
 * <p><b>旧格式兼容</b>：早期版本存储是纯数组；仓储读取时判型，
 * 数组自动迁移为 {@code context=""} 的文档（见 LettuceMemoryRepository）。
 *
 * @param context  会话滚动摘要：被渐进摘要裁剪掉的旧消息内容（可能为空串）
 * @param strategy 本会话的记忆抽取策略名（会话级覆盖；空串表示用全局缺省）
 * @param owner    会话归属（缺省 "default"）
 * @param entries  现存的工作记忆条目（按写入顺序）
 */
public record WorkingMemoryDocument(
        String context,
        String strategy,
        String owner,
        List<WorkingMemoryEntry> entries) {

    /** 紧凑构造器：各字段 null 兜底（含旧格式/半成品 JSON 的容错）。 */
    public WorkingMemoryDocument {
        context = context == null ? "" : context;
        strategy = strategy == null ? "" : strategy;
        owner = owner == null || owner.isBlank() ? "default" : owner.trim();
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** 兼容旧调用的三参构造器（owner 走缺省 default）。 */
    public WorkingMemoryDocument(String context, String strategy, List<WorkingMemoryEntry> entries) {
        this(context, strategy, null, entries);
    }

    /** 空文档工厂。 */
    public static WorkingMemoryDocument empty() {
        return new WorkingMemoryDocument("", "", "default", List.of());
    }

    /** entries 总字符量——渐进摘要的触发与保留比例都以字符近似 token 计量。 */
    public int totalChars() {
        return entries.stream().mapToInt(e -> e.content().length()).sum();
    }
}

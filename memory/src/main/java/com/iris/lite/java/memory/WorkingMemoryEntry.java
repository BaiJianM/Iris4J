package com.iris.lite.java.memory;

import java.util.UUID;

/**
 * 工作记忆条目：会话内的一条短期上下文。
 *
 * <p>工作记忆按 (namespace, sessionId) 聚合，存储为 {@link WorkingMemoryDocument}，
 * 本类即其中的一个条目。
 *
 * <p><b>id 的用途</b>：工作记忆语义索引
 * 以 (sessionId, entryId) 定位向量文档——id 必须在条目生命周期内稳定，
 * 因此只在创建/迁移落库时生成一次，后续反序列化直接沿用。
 *
 * @param id        条目唯一标识（UUID），缺省自动生成
 * @param content   记忆文本
 * @param createdAt 写入时间（epoch millis）
 * @param owner     条目归属：写入时随条目记录，异步抽取
 *                  长期记忆时继承，实现"谁的记忆归谁"的多用户隔离；
 *                  null/blank 归一化为 "default"
 */
public record WorkingMemoryEntry(String id, String content, long createdAt, String owner) {

    /**
     * 紧凑构造器：内容必填，id/时间/owner 缺省补齐。
     *
     * <p>内容为空的记忆没有意义，直接拒绝而不是存一条空记录。
     */
    public WorkingMemoryEntry {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("工作记忆内容不能为空");
        }
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt <= 0) {
            createdAt = System.currentTimeMillis();
        }
        // ownerId 归一化：缺省归入 "default"，旧 JSON 无该字段时反序列化同样兜底
        if (owner == null || owner.isBlank()) {
            owner = "default";
        }
        owner = owner.trim();
    }

    /** 兼容旧调用的三参构造器（owner 走缺省 default）。 */
    public WorkingMemoryEntry(String id, String content, long createdAt) {
        this(id, content, createdAt, null);
    }

    /** 兼容旧调用的两参构造器（id 自动生成）。 */
    public WorkingMemoryEntry(String content, long createdAt) {
        this(null, content, createdAt, null);
    }

    /**
     * 用当前时间创建一条工作记忆。
     *
     * <p>应用层写入走这个方法——调用方不必自己关心时间戳与 id 生成。
     */
    public static WorkingMemoryEntry of(String content) {
        return new WorkingMemoryEntry(content, System.currentTimeMillis());
    }

    /** 带 owner 的写入工厂（多用户隔离场景，抽取链路据此继承归属）。 */
    public static WorkingMemoryEntry of(String content, String owner) {
        return new WorkingMemoryEntry(null, content, System.currentTimeMillis(), owner);
    }
}

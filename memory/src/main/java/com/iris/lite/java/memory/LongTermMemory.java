package com.iris.lite.java.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 长期记忆：跨会话持久化、可检索的一条记忆。
 *
 * <p><b>与工作记忆的区别</b>：工作记忆绑定会话、会话结束即失去意义；
 * 长期记忆跨会话有效，用于积累用户偏好、背景信息、约束条件等
 * 需要长期记住的内容。
 *
 * <p><b>模型字段</b>：memoryType 枚举
 * （episodic/semantic）、topics/entities 标签、episodic 专属 eventDate、
 * 抽取来源策略 extractionStrategy、精确去重指纹 memoryHash。
 *
 * <p><b>兼容字段 {@code type}</b>：自由字符串分类（preference/background/
 * constraint），保留以兼容旧 API 与旧数据；新写入路径应优先使用 memoryType 枚举。
 *
 * <p><b>不设 pinned/accessCount/metadata 字段</b>：本项目无置顶功能、无容量
 * 淘汰机制，这些字段暂无消费方——需要时再加，避免过度设计。
 *
 * @param id                 唯一标识（UUID），由应用层生成
 * @param namespace          命名空间，用于隔离
 * @param type               兼容自由分类（preference / background / constraint 等）
 * @param content            记忆文本
 * @param createdAt          写入时间（epoch millis），检索时按此倒序
 * @param memoryType         记忆类型枚举（episodic/semantic）
 * @param topics             主题标签（禁逗号——TAG 索引编码约定）
 * @param entities           涉及的实体/人名（禁逗号）
 * @param eventDate          episodic 事件的绝对日期（ISO 8601），semantic 为 null
 * @param extractionStrategy 产出本条记忆的策略名（手动写入缺省 discrete）
 * @param memoryHash         内容 SHA-256 指纹（精确去重先行，向量去重兜底）
 * @param owner              记忆归属：标识与该记忆关联的
 *                           用户/实体，多用户隔离与按 owner 检索过滤的依据；
 *                           null/blank 归一化为 "default"
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record LongTermMemory(
        String id,
        String namespace,
        String type,
        String content,
        long createdAt,
        MemoryType memoryType,
        List<String> topics,
        List<String> entities,
        String eventDate,
        String extractionStrategy,
        String memoryHash,
        String owner) {

    /**
     * 紧凑构造器：必填校验 + 旧数据兼容兜底。
     *
     * <p>旧版本 JSON 只有 5 个字段——反序列化时新增字段为 null，
     * 这里统一兜底（memoryType→SEMANTIC、列表→空、策略→discrete），
     * 保证旧记录读取不炸。
     */
    public LongTermMemory {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("记忆 id 不能为空");
        }
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace 不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("长期记忆内容不能为空");
        }
        if (type == null || type.isBlank()) {
            type = "note";
        }
        if (createdAt <= 0) {
            createdAt = System.currentTimeMillis();
        }
        if (memoryType == null) {
            memoryType = MemoryType.SEMANTIC;
        }
        topics = normalizeTags(topics, "topics");
        entities = normalizeTags(entities, "entities");
        if (eventDate != null && eventDate.isBlank()) {
            eventDate = null;
        }
        if (extractionStrategy == null || extractionStrategy.isBlank()) {
            extractionStrategy = "discrete";
        }
        // 内容指纹：调用方未提供时按内容现算（手动写入路径），保证精确去重恒可用
        memoryHash = memoryHash == null || memoryHash.isBlank()
                ? sha256(content) : memoryHash;
        // ownerId 归一化：缺省归入 "default"，旧版本 JSON 无该字段时
        // 反序列化得到 null，同样落到 default——旧记忆在无 owner 过滤的检索中照常可见
        if (owner == null || owner.isBlank()) {
            owner = "default";
        }
        owner = owner.trim();
    }

    /** 兼容旧调用的 11 参构造器（owner 走缺省 default）。 */
    public LongTermMemory(String id, String namespace, String type, String content, long createdAt,
                          MemoryType memoryType, List<String> topics, List<String> entities,
                          String eventDate, String extractionStrategy, String memoryHash) {
        this(id, namespace, type, content, createdAt, memoryType, topics, entities,
                eventDate, extractionStrategy, memoryHash, null);
    }

    /** 兼容旧调用的 5 参构造器（新增字段走缺省值）。 */
    public LongTermMemory(String id, String namespace, String type, String content, long createdAt) {
        this(id, namespace, type, content, createdAt, null, null, null, null, null, null);
    }

    /**
     * 标签归一化：去空项、禁逗号（TAG 索引编码用逗号做分隔，
     * 当前虽不建 TAG 索引，也提前守住约定，避免未来加索引时洗数据）。
     */
    private static List<String> normalizeTags(List<String> tags, String field) {
        if (tags == null) {
            return List.of();
        }
        for (String tag : tags) {
            if (tag != null && tag.contains(",")) {
                throw new IllegalArgumentException(field + " 标签不允许包含逗号: " + tag);
            }
        }
        return tags.stream().filter(t -> t != null && !t.isBlank()).toList();
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 在 JVM 内置 Provider 中恒可用，走到这里属于环境异常
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}

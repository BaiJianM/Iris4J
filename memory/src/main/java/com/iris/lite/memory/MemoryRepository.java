package com.iris.lite.memory;

import java.util.List;

/**
 * 记忆仓储端口。
 *
 * <p><b>边界</b>：实现放 infrastructure 层
 * （{@code LettuceMemoryRepository}），应用服务与 REST/MCP 不感知
 * 底层 Redis 客户端与存储细节。
 *
 * <p><b>隔离维度</b>：工作记忆按 (namespace, sessionId) 隔离，
 * 长期记忆按 namespace 隔离——前者是会话私有的，后者是跨会话共享的。
 */
public interface MemoryRepository {

    /** 向指定会话的工作记忆追加一条。 */
    void appendWorkingMemory(String namespace, String sessionId, WorkingMemoryEntry entry);

    /**
     * 读取指定会话的完整工作记忆文档（含滚动摘要 context 与会话策略）；
     * 无记录返回空文档。
     */
    WorkingMemoryDocument getWorkingMemoryDocument(String namespace, String sessionId);

    /**
     * 读取指定会话的全部工作记忆条目（按写入顺序）；无记录返回空列表。
     *
     * <p>默认方法从文档视图派生，兼容只关心条目的旧调用方。
     */
    default List<WorkingMemoryEntry> getWorkingMemory(String namespace, String sessionId) {
        return getWorkingMemoryDocument(namespace, sessionId).entries();
    }

    /**
     * 整体替换会话工作记忆文档（渐进摘要写回 context/entries 用）；
     * 仓储实现负责刷新会话 TTL。
     */
    void replaceWorkingMemory(String namespace, String sessionId, WorkingMemoryDocument document);

    /** 设置会话级记忆抽取策略（会话级覆盖，空串表示回落全局缺省）。 */
    void saveWorkingMemoryStrategy(String namespace, String sessionId, String strategy);

    /**
     * 尝试获取会话操作短锁（SET NX PX + 有限重试）。
     * 摘要写回、策略设置这类"读-改-写"操作与高频 append 互斥用。
     *
     * @return true=拿到锁；false=锁被他人持有（放弃当前尝试，重试幂等）
     */
    boolean tryWorkingMemoryLock(String namespace, String sessionId);

    /** 释放会话操作短锁（与 {@link #tryWorkingMemoryLock} 配对；不存在时静默）。 */
    void releaseWorkingMemoryLock(String namespace, String sessionId);

    /** 清空指定会话的工作记忆（含其语义索引向量）。 */
    void clearWorkingMemory(String namespace, String sessionId);

    /** 写入一条长期记忆，返回带 id 的完整记忆。 */
    LongTermMemory saveLongTermMemory(LongTermMemory memory);

    /** 删除一条长期记忆（本体 + 语义向量）；记忆不存在时静默返回。 */
    void deleteLongTermMemory(String namespace, String memoryId);

    /**
     * 检索长期记忆，按创建时间倒序（keyword）或相似度（semantic/hybrid），
     * 最多返回 limit 条。
     *
     * @param owner 记忆归属（ownerId 层）：null = 不过滤（返回全部 owner 的记忆，
     *              兼容旧语义）；传值 = 只返回该 owner 的记忆（索引层 TAG 精确匹配，
     *              绝不做语义模糊——owner 过滤走索引层 TAG 精确匹配）
     * @param query 关键词；空字符串表示不过滤，返回最近的记忆（mode 强制 KEYWORD）
     * @param mode  检索模式（semantic/keyword/hybrid），null 视为 hybrid
     */
    List<LongTermMemory> searchLongTermMemory(
            String namespace, String owner, String query, int limit, SearchMode mode);

    /**
     * 兼容旧调用的四参检索（owner=null 不过滤，返回全部）。
     */
    default List<LongTermMemory> searchLongTermMemory(String namespace, String query, int limit, SearchMode mode) {
        return searchLongTermMemory(namespace, null, query, limit, mode);
    }

    /**
     * 兼容旧调用的三参检索（缺省 hybrid 模式）。
     */
    default List<LongTermMemory> searchLongTermMemory(String namespace, String query, int limit) {
        return searchLongTermMemory(namespace, query, limit, SearchMode.HYBRID);
    }

    /**
     * 工作记忆语义检索：在指定会话内做 KNN，
     * 返回语义最相关的条目。未启用索引/无 Embedder 时返回空列表。
     */
    List<WorkingMemoryEntry> searchWorkingMemory(String namespace, String sessionId, String query, int limit);
}

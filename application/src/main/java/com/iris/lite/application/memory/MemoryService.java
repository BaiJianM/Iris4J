package com.iris.lite.application.memory;

import com.iris.lite.memory.LongTermMemory;
import com.iris.lite.memory.SearchMode;
import com.iris.lite.memory.WorkingMemoryDocument;
import com.iris.lite.memory.WorkingMemoryEntry;

import java.util.List;

/**
 * 统一记忆服务，REST 与 MCP 共用。
 *
 * <p>工作记忆绑定会话（namespace + sessionId），带滚动摘要（context）与
 * 会话级抽取策略；长期记忆跨会话、按 namespace 隔离，支持三模式检索。
 */
public interface MemoryService {

    /**
     * 向指定会话的工作记忆追加一条文本，并联动抽取/摘要触发钩子。
     *
     * @return 新条目的 id（异步补写摘要等场景按 id 回写内容用）
     */
    String saveWorkingMemory(String namespace, String sessionId, String content);

    /**
     * 向指定会话追加工作记忆并设置会话级抽取策略。
     *
     * @param strategy 记忆策略名（discrete/summary/preferences/custom）；
     *                 null 表示不改会话现有策略
     * @return 新条目的 id
     */
    String saveWorkingMemory(String namespace, String sessionId, String content, String strategy);

    /**
     * 向指定会话追加工作记忆，同时设置会话级策略与 owner（ownerId 层）。
     *
     * @param owner    记忆归属（标识用户/实体，多用户隔离）；null 表示不改归属，
     *                 会话首次出现 owner 时生效
     * @return 新条目的 id
     */
    String saveWorkingMemory(String namespace, String sessionId, String content,
                             String strategy, String owner);

    /**
     * 按条目 id 精确替换工作记忆条目内容（持锁读-改-写，与并发 append 互斥）。
     *
     * <p>场景：会话留痕异步摘要——先同步存全文（保真），摘要算出后回写为
     * 「摘要 + 完整答案」映射。条目已不存在（被清理/并发摘要）时静默跳过。
     */
    void updateWorkingMemoryEntry(String namespace, String sessionId, String entryId, String newContent);

    /** 设置会话级记忆抽取策略（后续抽取按此策略执行）。 */
    void saveWorkingMemoryStrategy(String namespace, String sessionId, String strategy);

    /** 读取指定会话的完整工作记忆文档（context + strategy + entries）。 */
    WorkingMemoryDocument getWorkingMemory(String namespace, String sessionId);

    /** 清空指定会话的工作记忆。 */
    void clearWorkingMemory(String namespace, String sessionId);

    /** 手动写入一条长期记忆，返回完整记忆（含生成的 id）。 */
    LongTermMemory saveLongTermMemory(String namespace, String type, String content);

    /**
     * 手动写入一条带归属的长期记忆（ownerId 层）。
     *
     * @param owner 记忆归属（标识用户/实体）；null 归入 default
     */
    LongTermMemory saveLongTermMemory(String namespace, String type, String content, String owner);

    /** 删除一条长期记忆（本体 + 向量）。 */
    void deleteLongTermMemory(String namespace, String memoryId);

    /**
     * 检索长期记忆，按创建时间倒序（keyword）或相似度（semantic/hybrid）。
     *
     * @param mode 检索模式；null 视为 hybrid
     */
    List<LongTermMemory> searchLongTermMemory(String namespace, String query, int limit, SearchMode mode);

    /**
     * 带 owner 过滤的检索（ownerId 层）。
     *
     * @param owner 记忆归属；null = 不过滤（全部 owner 的记忆）；
     *              传值 = 只返回该 owner 的记忆
     */
    List<LongTermMemory> searchLongTermMemory(
            String namespace, String owner, String query, int limit, SearchMode mode);

    /** 工作记忆语义检索（会话内 KNN；索引未启用返回空列表）。 */
    List<WorkingMemoryEntry> searchWorkingMemory(String namespace, String sessionId, String query, int limit);

    /**
     * 立即抽取指定会话的工作记忆并晋升为长期记忆（同步执行）。
     *
     * @param customPrompt custom 策略的自定义 prompt；其他策略忽略
     * @return 本次新写入的长期记忆；抽取器未启用时返回空列表
     */
    List<LongTermMemory> extractFromSession(String namespace, String sessionId, String customPrompt);

    /**
     * 两参形式的抽取入口（不带自定义 prompt）。
     */
    default List<LongTermMemory> extractFromSession(String namespace, String sessionId) {
        return extractFromSession(namespace, sessionId, null);
    }
}

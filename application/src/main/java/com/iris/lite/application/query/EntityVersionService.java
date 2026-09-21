package com.iris.lite.application.query;

import java.util.List;
import java.util.Map;

/**
 * 实体数据版本服务（数据版本守卫）。
 *
 * <p><b>解决什么问题</b>：LLM 响应语义缓存的"旧答案"问题——缓存条目按 prompt
 * 相似度命中，对底层数据变更毫无感知；数据更新后语义命中的回答可能已过期，
 * 单靠 TTL 与手动删除无法与数据变更联动。
 *
 * <p><b>机制</b>：CDC 消费点每成功投影一个实体的变更即 {@code bump} 该实体的
 * 版本计数器（{@code iris:{ns}:ver:{entity}}，原子 INCR）；缓存写入时记录依赖
 * 实体当时的版本，读取命中后校验版本一致才通过。
 */
public interface EntityVersionService {

    /** 原子自增实体版本，返回新版本号（从 1 起）。 */
    long bump(String namespace, String entity);

    /** 当前实体版本；从未变更过（key 不存在）为 0。 */
    long current(String namespace, String entity);

    /** 批量读取多个实体的当前版本（保持传入顺序）。 */
    Map<String, Long> currentAll(String namespace, List<String> entities);
}

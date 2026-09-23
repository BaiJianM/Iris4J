package com.iris4j.application.agent;

import com.iris4j.shared.util.Digests;

import java.util.List;
import java.util.Map;

/**
 * 动态 agent key 存储端口（agent key 动态管理）。
 *
 * <p><b>为什么是端口</b>：application 层不出现 Redis 命令（模块边界），
 * 实现在 infrastructure（{@code LettuceAgentKeyStore}，Redis HASH）。
 * api 层的注册表与管理控制器都经由本端口读写，换存储介质时业务零改动。
 *
 * <p><b>存储形态（实现方负责）</b>：{@code iris:security:agent-keys} HASH，
 * field = key 的 SHA-256 十六进制指纹（{@link #fingerprint}），
 * value = {@code {"agentId":..,"tags":[..],"updatedAt":..}}。
 * <b>明文 key 不落 Redis</b>：指纹单向，投影存储泄漏不泄漏凭证本体；
 * 明文只存在于调用方与 yml 静态配置（同等信任级别）。
 */
public interface AgentKeyStore {

    /**
     * agent key 明文 -> SHA-256 十六进制指纹。
     *
     * <p>用指纹做存储主键与删除定位：管理接口的 URL/日志里只出现指纹，
     * 不会把明文 key 写进访问日志。同一把 key 恒得同一指纹，天然去重。
     */
    static String fingerprint(String key) {
        return Digests.sha256Hex(key);
    }

    /**
     * 全量加载动态 agent key 条目。
     *
     * @return 指纹 -> 条目；注册表空时返回空 Map（不返回 null）
     */
    Map<String, AgentKeyEntry> loadAll();

    /**
     * 保存（新增或覆盖）一个动态 agent key 条目。
     *
     * <p>同一指纹重复保存 = 覆盖（改 tags 的热更新路径，正是本能力的目标场景）。
     *
     * @param fingerprint key 的 SHA-256 指纹（{@link #fingerprint}）
     * @param agentId     agent 标识
     * @param tags        access tags（调用方已校验，不含通配 {@code *}）
     */
    void save(String fingerprint, String agentId, List<String> tags);

    /**
     * 删除一个动态 agent key 条目。
     *
     * @param fingerprint key 的 SHA-256 指纹
     * @return true=确实存在并已删除；false=指纹不存在（可能是静态配置的 key，动态层管不到）
     */
    boolean delete(String fingerprint);
}

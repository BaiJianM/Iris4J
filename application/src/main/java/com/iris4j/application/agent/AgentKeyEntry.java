package com.iris4j.application.agent;

import java.time.Instant;
import java.util.List;

/**
 * 动态 agent key 条目——存储层的读取模型。
 *
 * <p><b>刻意不含明文 key</b>：条目会在管理列表接口里展示，
 * 明文 key 一旦进了响应体就有被记进访问日志/抓包的风险；
 * 指纹（SHA-256 前缀）足以让运维分辨"这是哪把 key"。
 *
 * @param fingerprint key 的 SHA-256 十六进制指纹（存储主键）
 * @param agentId     agent 标识（同一 agent 可有多把 key，轮换 = 新旧并存）
 * @param tags        access tags（行级/字段级可见性授权，不含通配 {@code *}）
 * @param updatedAt   最后更新时间（ISO-8601，排查"谁在什么时候改了权限"）
 */
public record AgentKeyEntry(String fingerprint, String agentId, List<String> tags, String updatedAt) {

    public AgentKeyEntry {
        if (fingerprint == null || fingerprint.isBlank()) {
            // 指纹是本模型的主键，缺了必然是装配错误——宁可启动/请求期炸出来
            // 也不许带着 null 主键流动（Map.copyOf 拒 null key，报错信息还是空 NPE）
            throw new IllegalArgumentException("AgentKeyEntry.fingerprint 不能为空");
        }
        tags = tags == null ? List.of() : List.copyOf(tags);
        updatedAt = updatedAt == null ? Instant.now().toString() : updatedAt;
    }

    /** 指纹短形式（前 8 位），日志与人读场景用，避免整行刷屏。 */
    public String shortFingerprint() {
        return fingerprint.length() <= 8 ? fingerprint : fingerprint.substring(0, 8);
    }
}

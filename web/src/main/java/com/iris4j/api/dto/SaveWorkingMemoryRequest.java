package com.iris4j.api.dto;

/**
 * 工作记忆写入请求体。
 *
 * <p>content 非空校验在 {@code DefaultMemoryService} 层做
 * ——DTO 只负责承载传输结构，业务校验集中在应用层，
 * 这样 MCP 入口复用服务时能拿到同样的校验。
 *
 * @param strategy 会话级抽取策略（discrete/summary/preferences/custom，可空 = 不变）
 * @param owner    会话归属（ownerId 层，标识用户/实体；可空 = 不变，
 *                 会话首次出现 owner 时生效，缺省归入 default）
 */
public record SaveWorkingMemoryRequest(
        String namespace,
        String sessionId,
        String content,
        String strategy,
        String owner) {
}

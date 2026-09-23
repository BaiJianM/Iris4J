package com.iris.lite.java.api.dto;

/**
 * 长期记忆写入请求体。
 *
 * @param type  可省略，应用层缺省回落 "note"
 * @param owner 记忆归属（ownerId 层，标识用户/实体；可空 = 归入 default）
 */
public record SaveLongTermMemoryRequest(
        String namespace,
        String type,
        String content,
        String owner) {
}

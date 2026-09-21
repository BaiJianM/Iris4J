package com.iris.lite.application.ops;

/**
 * DLQ（死信队列）条目视图模型。
 *
 * <p>一条 DLQ 条目 = 一条反复处理失败、已达最大投递次数的 CDC 事件
 * + 供人工排查的诊断信息。诊断信息与原始载荷一起存，
 * 是为了让排查的人不必再回头翻历史日志去对消息 id。
 *
 * @param namespace     投影目标 namespace
 * @param entity        投影目标实体名
 * @param dlqStream     DLQ 流名（{originStream}:dlq）
 * @param dlqId         DLQ 流内消息 id（重放成功后用于 XDEL）
 * @param originStream  原始 CDC stream 名
 * @param originGroup   原始 consumer group 名
 * @param originMsgId   原始 stream 中的消息 id
 * @param msgKey        原始 stream 消息的 field（Debezium record key）
 * @param payload       原始事件载荷（Debezium envelope JSON）
 * @param deliveryCount 投递次数（含首次）
 * @param failedAt      转 DLQ 时间（ISO-8601）
 * @param error         最近一次失败原因
 */
public record DlqEntry(
        String namespace,
        String entity,
        String dlqStream,
        String dlqId,
        String originStream,
        String originGroup,
        String originMsgId,
        String msgKey,
        String payload,
        int deliveryCount,
        String failedAt,
        String error) {
}

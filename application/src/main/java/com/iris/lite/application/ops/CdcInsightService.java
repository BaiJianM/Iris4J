package com.iris.lite.application.ops;

import java.util.List;

/**
 * CDC 管道观测端口（管理控制台）：source 列表与 PEL 明细，只读。
 *
 * <p>实现由 cdc 模块提供（CdcProperties 与 RedisAdapter 都在其依赖闭包内，
 * application 不应为读数新增对二者的依赖）。人工重试/转 DLQ 由消费循环的
 * 毒消息防护自动完成，本端口刻意不提供干预入口。
 */
public interface CdcInsightService {

    /**
     * 单个 source 的管道视图；计数取不到（stream/group 未创建）为 null——UI 显示"未知"而非 0。
     *
     * <p>lag 与 streamLen 的口径区别：lag 是主消费组尚未投递的事件数
     * （真实积压，消费跟上就归零）；streamLen 是 XLEN（stream 保留的事件总量，
     * Debezium 消费后不删除，只增不减，本质是历史累计）。
     */
    record SourceView(
            int index,
            String namespace,
            String stream,
            String entity,
            String group,
            String consumer,
            Long streamLen,
            Long lag,
            Long pending,
            Long dlqLen) {
    }

    /** PEL 中一条待处理消息。 */
    record PendingView(
            String id,
            String consumer,
            long idleMs,
            long deliveryCount) {
    }

    /** 全部 source 的管道视图（XLEN/XPENDING 摘要，O(1) 命令）。 */
    List<SourceView> sources();

    /**
     * 指定 source 的 PEL 明细（已投递未确认消息）。
     *
     * @param index source 在配置中的序号（与 sources() 的 index 对应）
     * @throws IllegalArgumentException index 越界
     */
    List<PendingView> pending(int index, int limit);
}

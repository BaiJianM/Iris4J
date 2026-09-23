package com.iris4j.cdc;

/**
 * 变更事件处理器，见方案 4.2。
 *
 * <p><b>为什么抽象成接口</b>：实时消费（{@code CdcConsumer}）与人工重放
 * （{@code DefaultDlqAdminService}）都要"把 envelope 变成投影"，
 * 抽成接口保证两者走同一份实现，行为不会分叉。
 */
public interface ChangeEventHandler {

    /**
     * 处理一条变更事件，将其投影到 Redis（upsert / delete）。
     *
     * <p><b>抛异常 = 处理失败</b>：调用方据此决定是否 XACK。
     * 数据不完整类问题应记日志后静默跳过，不要抛异常（详见实现类注释）。
     *
     * @param event     变更事件
     * @param namespace 命名空间
     * @param entity    实体名
     */
    void handle(ChangeEvent event, String namespace, String entity);
}

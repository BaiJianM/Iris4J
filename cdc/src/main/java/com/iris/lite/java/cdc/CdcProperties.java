package com.iris.lite.java.cdc;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * CDC 消费配置，前缀 {@code iris.cdc}。
 *
 * <p><b>多源支持</b>：一个 {@link Source} 对应一个数据源
 * （MySQL / PostgreSQL），各自消费独立的 Redis Stream，
 * 投影到独立的 namespace/entity。多源之间互不阻塞——每个源一个消费线程。
 *
 * <p><b>可靠性参数（毒消息防护，见 {@link CdcConsumer#reclaimPending()}）</b>：
 * 处理失败的消息留在 PEL，由 reclaim 任务按 {@code reclaim-interval-ms} 周期扫描。
 * 生产建议 max-deliveries=3、claim-idle-ms=30000；
 * 本地调试可用 2/3000 加速毒消息进入 DLQ（否则要等好几分钟才能看到 DLQ 有东西）。
 *
 * <p><b>stream-maxlen</b>：stream 的<b>保留上限</b>（XTRIM MAXLEN 近似裁剪），
 * 防止长期运行 stream 无限增长吃穿内存。设为 0 关闭裁剪（不推荐）。
 *
 * <p><b>它不是"已消费事件的裁剪上限"</b>：XTRIM 只认「保留最新 N 条」，
 * 不区分是否消费过。故实际生效上限由 {@code CdcConsumer.trimStream} 取
 * {@code max(stream-maxlen, lag + pending + 1000)} —— 全量导入等积压场景下会自动抬高，
 * 绝不会为了省内存去裁未消费的事件。即：<b>本项是"内存目标"，不是"数据保留硬约束"。</b>
 */
@ConfigurationProperties(prefix = "iris.cdc")
public record CdcProperties(List<Source> sources, Integer maxDeliveries, Integer claimIdleMs,
                            Integer streamMaxlen) {

    /**
     * 紧凑构造器：集合与数值兜底。
     *
     * <p>用装箱 {@code Integer} 是为了区分"没配置"（null -> 取默认）
     * 与"显式配了 0"——虽然两者最终都回落到默认值，但语义来源清晰。
     */
    public CdcProperties {
        if (sources == null) {
            sources = List.of();
        }
        if (maxDeliveries == null || maxDeliveries <= 0) {
            maxDeliveries = 3;
        }
        if (claimIdleMs == null || claimIdleMs <= 0) {
            claimIdleMs = 30000;
        }
        // stream 裁剪上限：0/负值 = 关闭裁剪（不推荐，长期运行 stream 只增不减）
        if (streamMaxlen == null || streamMaxlen <= 0) {
            streamMaxlen = 100000;
        }
    }

    /**
     * 单个 CDC 源。
     *
     * @param namespace 投影目标 namespace
     * @param stream    Debezium 写入的 Redis Stream 名（= topic 名）
     * @param entity    投影目标实体名
     * @param group     Redis consumer group
     * @param consumer  consumer 名
     * @param blockMs   XREADGROUP BLOCK 毫秒，须小于 redis.timeout-ms
     */
    public record Source(
            String namespace,
            String stream,
            String entity,
            String group,
            String consumer,
            int blockMs) {

        /**
         * 紧凑构造器：stream 与 entity 必填（定位数据的必需信息），其余给默认值。
         *
         * <p><b>block-ms 必须小于 redis.timeout-ms</b>：BLOCK 到时返回空是正常行为，
         * 若 blockMs 大于命令超时，Lettuce 会先抛命令超时异常，
         * 导致消费循环反复报错重启——配置错了会看到"每 blockMs 一次异常"的现象。
         */
        public Source {
            if (namespace == null || namespace.isBlank()) {
                // fail-fast：数据写入哪个命名空间必须显式声明——给默认值会让漏配
                // 静默落到错误的空间，错配即脏数据
                throw new IllegalArgumentException(
                        "iris.cdc.sources[].namespace 不能为空（必须显式声明数据归属的命名空间）");
            }
            if (stream == null || stream.isBlank()) {
                throw new IllegalArgumentException("iris.cdc.sources[].stream 不能为空");
            }
            if (entity == null || entity.isBlank()) {
                throw new IllegalArgumentException("iris.cdc.sources[].entity 不能为空");
            }
            if (group == null || group.isBlank()) {
                group = "redis-iris-java";
            }
            if (consumer == null || consumer.isBlank()) {
                consumer = "cdc-1";
            }
            if (blockMs <= 0) {
                blockMs = 2000;
            }
        }
    }
}

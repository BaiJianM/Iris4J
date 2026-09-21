package com.iris.lite.application.query;

import java.util.List;
import java.util.Map;

/**
 * 批量投影提交端口：把一批 CDC 变更事件用 pipeline 一次提交。
 *
 * <p><b>解决什么问题</b>：逐事件提交路径每条要 5+N 次往返（JSON.SET + SMEMBERS +
 * N×DEL + INCR + XACK），全量导入期是吞吐瓶颈（逐条约 1000 条/秒，热态 2200+）。
 * pipeline 化后整批 2 次往返，与批大小基本无关。
 *
 * <p><b>语义与逐条路径完全对齐</b>（{@code DefaultChangeEventHandler#handle}）：
 * <ul>
 *   <li>c/u/r 取 after 整文档覆盖；d 取 before 主键删除；</li>
 *   <li>after 缺失 / 缺主键 = 跳过（视为已处理，可 XACK，与逐条路径的 warn+skip 一致）；</li>
 *   <li>序列化失败 / 命令失败 = 该条未投影（调用方不 XACK，留 PEL 重试/转 DLQ）；</li>
 *   <li>投影成功的实体：版本 bump + 实体缓存失效（可通过
 *       {@code iris.cdc.cache-invalidation-enabled} 关闭失效部分）；</li>
 *   <li>同批同实体 bump 一次：版本只用于新鲜度比较（命中后比对），单调即可，
 *       无需与事件数一一对应。</li>
 * </ul>
 *
 * <p><b>DLQ 重放不走本端口</b>：重放调用量小，继续走逐条
 * {@code ChangeEventHandler#handle}，保证「重放与实时消费行为一致」的设计不变——
 * 批量路径是实时消费的吞吐优化，不是第二条语义。
 */
public interface ProjectionBatchApplier {

    /**
     * pipeline 提交一批变更。
     *
     * @param ops 变更清单（顺序即投影视图内的应用顺序；同主键后者覆盖前者）
     * @return 与 ops 等长的结果列表：true = 该条已投影（或按语义跳过），可 XACK；
     *         false = 投影失败，留在 PEL
     * @throws io.lettuce.core.RedisException 连接级故障（整批未提交，
     *         调用方应让全部消息留在 PEL）
     */
    List<Boolean> apply(List<ProjectionWrite> ops);

    /** 一条待投影变更：与 Debezium envelope 的 op/before/after 一一对应。 */
    record ProjectionWrite(
            String namespace,
            String entity,
            String op,
            Map<String, Object> before,
            Map<String, Object> after) {
    }
}

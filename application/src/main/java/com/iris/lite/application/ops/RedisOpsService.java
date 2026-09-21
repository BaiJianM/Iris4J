package com.iris.lite.application.ops;

/**
 * Redis 运维服务端口（高可用/备份/演练），REST 与 MCP 共用。
 *
 * <p><b>首期 standalone</b>：提供持久化状态可观测与 BGSAVE 触发；副本、哨兵、
 * RPO/RTO 指标留生产化阶段。
 *
 * <p><b>为什么不做文件级备份</b>：应用进程不碰文件系统——
 * dump.rdb 与 appendonlydir 的 tar/还原由项目外运维脚本在宿主机完成
 * （tar 整个 /data 卷，含 dump.rdb + AOF + 消费组位点）。进程内做文件搬运会引入
 * 容器路径挂载与权限问题，且备份失败难以感知。
 */
public interface RedisOpsService {

    /** 采集运维快照（dbsize / 内存 / AOF / RDB 持久化状态）。 */
    RedisOpsSnapshot snapshot();

    /** 触发后台 RDB 快照（BGSAVE）。 */
    void bgsave();

    /** 探活（PING），供健康指示器感知 Redis 故障；任何异常返回 false。 */
    boolean reachable();
}

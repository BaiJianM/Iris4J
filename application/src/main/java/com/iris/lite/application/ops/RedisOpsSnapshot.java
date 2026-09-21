package com.iris.lite.application.ops;

/**
 * Redis 运维快照（高可用/备份/演练的可观测依据）。
 *
 * @param dbsize                   逻辑库 key 总数
 * @param redisVersion             Redis 服务端版本
 * @param uptimeSeconds            服务端已运行秒数
 * @param usedMemoryHuman          已用内存（人类可读，如 1.25M）
 * @param aofEnabled               是否开启 AOF（首期启用）
 * @param aofLastWriteStatus       AOF 最近一次写入状态（ok/eofaof/…，非 ok 需关注）
 * @param aofSizeHuman             AOF 当前体积（人类可读）
 * @param rdbLastSaveTime          最近一次 RDB 落盘时间（ISO-8601）
 * @param rdbLastBgsaveStatus      最近一次 BGSAVE 状态（ok/err…）
 * @param rdbChangesSinceLastSave  自上次 RDB 落盘以来的变更 key 数（越大越该备份）
 */
public record RedisOpsSnapshot(
        long dbsize,
        String redisVersion,
        long uptimeSeconds,
        String usedMemoryHuman,
        boolean aofEnabled,
        String aofLastWriteStatus,
        String aofSizeHuman,
        String rdbLastSaveTime,
        String rdbLastBgsaveStatus,
        long rdbChangesSinceLastSave) {
}

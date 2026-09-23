package com.iris4j.infrastructure.redis;

import com.iris4j.application.ops.RedisOpsService;
import com.iris4j.application.ops.RedisOpsSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * 基于 INFO/BGSAVE 命令的运维快照实现。
 *
 * <p><b>进程内只做可观测</b>，不做文件级备份——
 * dump.rdb 与 appendonlydir 的 tar/还原由宿主机脚本完成。
 * 应用不碰文件系统，避免容器权限与路径耦合问题。
 */
@Service
public class DefaultRedisOpsService implements RedisOpsService {

    private static final Logger log = LoggerFactory.getLogger(DefaultRedisOpsService.class);

    private final RedisAdapter redis;

    public DefaultRedisOpsService(RedisAdapter redis) {
        this.redis = redis;
    }

    /**
     * 采集运维快照：三次 INFO（persistence / memory / server）+ DBSIZE + LASTSAVE。
     *
     * <p>输出 debug 摘要是因为这份快照的核心诉求就是"现在该不该备份"：
     * {@code rdbChangesSinceLastSave} 越大表示距上次落盘的变更越多，
     * 一旦宕机丢的数据越多。
     */
    @Override
    public RedisOpsSnapshot snapshot() {
        Map<String, String> persistence = redis.info("persistence");
        Map<String, String> memory = redis.info("memory");
        Map<String, String> server = redis.info("server");

        RedisOpsSnapshot snapshot = new RedisOpsSnapshot(
                redis.dbsize(),
                server.getOrDefault("redis_version", "unknown"),
                parseLong(server.get("uptime_in_seconds")),
                memory.getOrDefault("used_memory_human", "unknown"),
                "1".equals(persistence.get("aof_enabled")),
                persistence.getOrDefault("aof_last_write_status", "unknown"),
                // Redis 8 INFO 无 aof_current_size_human，用字节数自行换算
                humanSize(firstNonBlank(persistence.get("aof_current_size_human"),
                        persistence.get("aof_current_size"))),
                Instant.ofEpochMilli(redis.lastSave().getTime()).toString(),
                persistence.getOrDefault("rdb_last_bgsave_status", "unknown"),
                parseLong(persistence.get("rdb_changes_since_last_save")));

        log.debug("Redis 运维快照 dbsize={} memory={} aof={}({}) rdb状态={} 距上次落盘变更={}",
                snapshot.dbsize(), snapshot.usedMemoryHuman(),
                snapshot.aofEnabled() ? "on" : "off", snapshot.aofLastWriteStatus(),
                snapshot.rdbLastBgsaveStatus(), snapshot.rdbChangesSinceLastSave());
        return snapshot;
    }

    /** 触发后台 RDB 快照（BGSAVE）。异步执行，不阻塞当前请求。 */
    @Override
    public void bgsave() {
        redis.bgsave();
        log.info("BGSAVE 已触发");
    }

    /**
     * 探活（PING）。供健康指示器感知 Redis 故障。
     *
     * <p><b>吞掉所有异常返回 false</b>：这是健康探针，抛异常会让
     * /actuator/health 直接 500 而不是优雅地报告 DOWN。
     * 但要在 debug 里留痕，否则真出故障时会失去排查线索。
     */
    @Override
    public boolean reachable() {
        try {
            return "PONG".equals(redis.ping());
        } catch (Exception e) {
            log.debug("Redis 探活失败: {}", e.getMessage(), e);
            return false;
        }
    }

    /** 取第一个非空值，用于兼容不同 Redis 版本字段名差异。 */
    private String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    /** 字节数换算人类可读格式（非数字输入原样返回，避免丢信息）。 */
    private String humanSize(String value) {
        if (value == null) {
            return "unknown";
        }
        try {
            long bytes = Long.parseLong(value.trim());
            if (bytes < 1024) {
                return bytes + "B";
            }
            if (bytes < 1024 * 1024) {
                return String.format("%.1fK", bytes / 1024.0);
            }
            return String.format("%.1fM", bytes / 1024.0 / 1024.0);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    /**
     * 安全解析 Long。
     *
     * <p>返回 0 而非抛异常：INFO 字段在不同 Redis 版本/配置下可能缺失
     * （如未开启 AOF 时没有 aof_* 字段），缺失不应导致整个快照失败。
     */
    private long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

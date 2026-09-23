package com.iris.lite.java.api.controller;

import com.iris.lite.java.application.ops.RedisOpsService;
import com.iris.lite.java.application.ops.RedisOpsSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Redis 运维入口（高可用/备份/演练）。
 *
 * <p>文件级备份/恢复由项目外运维脚本完成，本入口只提供状态可观测与 BGSAVE 触发。
 */
@RestController
@RequestMapping("/api/v1/redis")
public class RedisOpsController {

    private static final Logger log = LoggerFactory.getLogger(RedisOpsController.class);

    private final RedisOpsService redisOpsService;

    public RedisOpsController(RedisOpsService redisOpsService) {
        this.redisOpsService = redisOpsService;
    }

    /**
     * 运维快照：dbsize / 内存 / AOF / RDB 持久化状态。
     *
     * <p>判断"该不该备份"看 {@code rdbChangesSinceLastSave}
     * ——距上次落盘的变更 key 数，越大说明宕机丢的数据越多。
     */
    @GetMapping("/status")
    public RedisOpsSnapshot status() {
        return redisOpsService.snapshot();
    }

    /**
     * 触发后台 RDB 快照，返回最新快照便于确认 {@code rdb_last_bgsave_status}。
     *
     * <p>BGSAVE 是异步的，返回时快照未必完成，所以再取一次快照
     * 让调用方能立刻看到当前状态（随后可轮询 /status 确认）。
     */
    @PostMapping("/bgsave")
    public Map<String, Object> bgsave() {
        log.info("BGSAVE 触发请求（REST）");
        redisOpsService.bgsave();
        return Map.of("bgsave", "triggered", "snapshot", redisOpsService.snapshot());
    }
}

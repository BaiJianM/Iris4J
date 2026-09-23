package com.iris.lite.java.api.web;

import com.iris.lite.java.application.ops.RedisOpsService;
// Boot 4 把健康指示器从 actuate.health 挪到 health.contributor（spring-boot-health 模块）
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Redis 健康指示器。
 *
 * <p><b>为什么需要自定义</b>：应用使用手搓 Lettuce 连接（非 spring-data-redis），
 * Boot 自带的 RedisHealthIndicator 不生效：Redis 宕机时 /actuator/health
 * 仍会报 UP，属于"监控失灵"——
 * 这里补一个基于 PING 的指示器，使健康状态真实反映 Redis 可用性。
 *
 * <p>Bean 名 {@code "redis"}：让健康端点里这一项显示为 redis，
 * 与 Boot 原生命名保持一致，避免运维脚本要改。
 */
@Component("redis")
public class RedisHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(RedisHealthIndicator.class);

    private final RedisOpsService redisOpsService;

    public RedisHealthIndicator(RedisOpsService redisOpsService) {
        this.redisOpsService = redisOpsService;
    }

    /**
     * 返回 Redis 健康状态。
     *
     * <p>异常一律转成 DOWN 而非抛出——健康端点抛异常会让整个
     * /actuator/health 变成 500，掩盖其他组件的健康信息。
     */
    @Override
    public Health health() {
        try {
            boolean reachable = redisOpsService.reachable();
            if (!reachable) {
                log.warn("Redis 健康检查失败：PING 无响应");
            }
            return reachable
                    ? Health.up().build()
                    : Health.down().withDetail("ping", "失败").build();
        } catch (Exception e) {
            log.warn("Redis 健康检查异常: {}", e.getMessage(), e);
            return Health.down(e).build();
        }
    }
}

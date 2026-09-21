package com.iris.lite.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis 连接配置模型，前缀 {@code iris.redis}。
 *
 * <p><b>只承载配置，不含连接逻辑</b>：真正的连接装配在 {@code RedisConfig}，
 * 客户端（Lettuce）细节不泄漏到业务层。本 record 是纯数据。
 *
 * <p><b>紧凑构造器的默认值</b>：全部字段都有安全默认（本机 6379 / db0 / 2s 超时），
 * 这样本地开发可以只写 host，不必把每项都列全。
 */
@ConfigurationProperties(prefix = "iris.redis")
public record RedisProperties(
        String host,
        int port,
        String password,
        int database,
        long timeoutMs) {

    /**
     * 紧凑构造器：非法值回落默认值。
     *
     * <p><b>timeoutMs 的意义</b>：所有 Redis 命令的超时上限。
     * 注意它必须<b>大于</b> {@code iris.cdc.sources[].block-ms}，
     * 否则 XREADGROUP BLOCK 的正常超时返回会被误判成命令超时报错。
     */
    public RedisProperties {
        if (host == null || host.isBlank()) {
            host = "127.0.0.1";
        }
        if (port <= 0) {
            port = 6379;
        }
        if (database < 0) {
            database = 0;
        }
        if (timeoutMs <= 0) {
            timeoutMs = 2000;
        }
    }
}

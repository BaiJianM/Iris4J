package com.iris.lite.java.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 动态 Schema 装配：注册配置模型并开启轮询调度。
 *
 * <p><b>{@code @EnableScheduling} 放在这里（而非启动类）是刻意的</b>：
 * 本项目的三个定时任务——Schema mtime 轮询、CDC PEL 重查、动态 MCP 工具对账——
 * 都由各自所属模块的配置类开启调度。这样"关掉这个模块"时不会误伤其他模块的调度，
 * 也让"调度能力来自哪里"在代码里一目了然。
 */
@Configuration
@EnableConfigurationProperties(SchemaProperties.class)
@EnableScheduling
public class SchemaConfig {
}

package com.iris.lite.java.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Agent 演示启动入口。
 *
 * <p><b>与中间件的关系</b>：演示（聊天循环/SSE/配方自进化/console）不在中间件
 * 部署体系内，独立成 boot 模块。本应用 = 中间件全量（web HTTP 面 + 业务模块 +
 * runtime 依赖）<b>+</b> 演示组件——组件扫描 {@code com.iris.lite.java} 全域，
 * 中间件 fat jar（redis-iris-java-api）不含本模块代码，部署形态互不污染。
 *
 * <p><b>中间件 jar</b>：{@code java -jar api/target/redis-iris-java-api-*.jar}——
 * 外部接入方走 REST/MCP，无演示端点；
 * <b>演示 jar</b>：{@code java -jar demo/target/redis-iris-java-demo-*.jar}——
 * 全家桶（含 /api/v1/agent/* 演示端点与 console 前端），用于演示。
 *
 * <p>{@code @EnableScheduling} 无需重复声明：由中间件 infrastructure 的
 * SchemaConfig 统一开启（AgentKeyRegistry 对账、CDC 调度同源）。
 */
@SpringBootApplication(scanBasePackages = "com.iris.lite.java")
@ConfigurationPropertiesScan(basePackages = "com.iris.lite.java")
public class DemoApplication {

    private static final Logger log = LoggerFactory.getLogger(DemoApplication.class);

    public static void main(String[] args) {
        long startAt = System.currentTimeMillis();
        SpringApplication.run(DemoApplication.class, args);
        log.info("redis-iris-java demo 启动完成，耗时 {} ms", System.currentTimeMillis() - startAt);
    }
}

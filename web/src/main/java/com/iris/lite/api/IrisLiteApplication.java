package com.iris.lite.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * iris-lite 单体启动入口。
 *
 * <p><b>统一根包 {@code com.iris.lite}</b>：使 application / context / cdc /
 * infrastructure / web 各模块的组件都能被扫描注册。模块拆分仅用于代码边界隔离，
 * 运行时装配为单个 JAR。
 *
 * <p><b>{@code @ConfigurationPropertiesScan}</b>：显式扫描 {@code iris.*} 配置 record
 * （CdcProperties / SchemaProperties / RedisProperties 分布在各模块）。
 * 不加这个注解，跨模块的配置 record 不会被注册成 bean。
 */
@SpringBootApplication(scanBasePackages = "com.iris.lite")
@ConfigurationPropertiesScan(basePackages = "com.iris.lite")
public class IrisLiteApplication {

    private static final Logger log = LoggerFactory.getLogger(IrisLiteApplication.class);

    public static void main(String[] args) {
        long startAt = System.currentTimeMillis();
        SpringApplication.run(IrisLiteApplication.class, args);
        // 启动耗时是"改了配置后重启是否变慢"的第一手观测点
        log.info("iris-lite 启动完成，耗时 {} ms", System.currentTimeMillis() - startAt);
    }
}

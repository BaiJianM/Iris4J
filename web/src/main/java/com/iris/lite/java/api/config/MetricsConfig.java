package com.iris.lite.java.api.config;

import com.iris.lite.java.shared.metrics.IrisMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * 指标装配：把 Boot 管理的 MeterRegistry 注入 shared 的 {@link IrisMetrics} 门面。
 *
 * <p>放在 api（唯一有 Spring Boot 完整上下文的组装层）：
 * cdc / application 模块只依赖 micrometer-core 静态调用，
 * registry 从哪来、是不是 Prometheus 实现都由这里决定。
 */
@Configuration
public class MetricsConfig {

    public MetricsConfig(MeterRegistry meterRegistry) {
        IrisMetrics.install(meterRegistry);
    }
}

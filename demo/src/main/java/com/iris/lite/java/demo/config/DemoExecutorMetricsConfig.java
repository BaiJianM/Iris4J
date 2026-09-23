package com.iris.lite.java.demo.config;

import com.iris.lite.java.api.controller.AgentController;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.springframework.context.annotation.Configuration;

/**
 * 演示线程池指标装配：AgentController 心跳池注册 Micrometer executor 指标。
 *
 * <p>心跳池是 AgentController 的 private static 字段，经静态访问器暴露；
 * 该池随演示链路独立装配，中间件 web 模块的
 * ExecutorMetricsConfig 只保留记忆抽取/会话摘要两个中间件池。
 */
@Configuration
public class DemoExecutorMetricsConfig {

    public DemoExecutorMetricsConfig(MeterRegistry meterRegistry) {
        new ExecutorServiceMetrics(
                AgentController.heartbeatPool(), "iris.agent.heartbeat", Tags.empty())
                .bindTo(meterRegistry);
    }
}

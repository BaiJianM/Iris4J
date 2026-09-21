package com.iris.lite.api.config;

import com.iris.lite.application.memory.MemoryExtractionService;
import com.iris.lite.application.memory.SessionSummarizationService;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;

/**
 * 中间件线程池指标装配：两个自建池统一注册 Micrometer executor 指标，
 * 暴露 {@code executor.active} / {@code executor.queued} / {@code executor.pool.size} /
 * {@code executor.queue.remaining} 等 gauge（name 标签区分池）。
 *
 * <p><b>为什么放 web</b>：模块纪律上 application 不依赖 micrometer，
 * 只以 {@code workerExecutor()} 暴露池本身；registry 从哪来、是不是
 * Prometheus 实现都由装配层决定（与 {@link MetricsConfig} 的
 * IrisMetrics 接线同一纪律）。
 *
 * <p><b>看板用法</b>：{@code GET /actuator/metrics/executor.queued?tag=name:iris.memory.extractor}
 * 直接看单个池的积压；console 指标页可按 name 标签区分曲线。
 *
 * <p>Agent 演示心跳池的 gauge 随演示链路迁至 demo 模块
 * （{@code com.iris.lite.demo.config.DemoExecutorMetricsConfig}）。
 */
@Configuration
public class ExecutorMetricsConfig {

    public ExecutorMetricsConfig(
            MeterRegistry meterRegistry,
            ObjectProvider<MemoryExtractionService> extractorProvider,
            ObjectProvider<SessionSummarizationService> summarizerProvider) {
        MemoryExtractionService extractor = extractorProvider.getIfAvailable();
        if (extractor != null) {
            new ExecutorServiceMetrics(
                    extractor.workerExecutor(), "iris.memory.extractor", Tags.empty())
                    .bindTo(meterRegistry);
        }
        SessionSummarizationService summarizer = summarizerProvider.getIfAvailable();
        if (summarizer != null) {
            new ExecutorServiceMetrics(
                    summarizer.workerExecutor(), "iris.memory.summarizer", Tags.empty())
                    .bindTo(meterRegistry);
        }
    }
}

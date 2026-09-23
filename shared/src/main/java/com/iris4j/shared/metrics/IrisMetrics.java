package com.iris4j.shared.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import com.iris4j.shared.error.IrisException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 业务指标统一门面（T7 可观测性）。
 *
 * <p><b>为什么是静态门面而不是注入 MeterRegistry</b>：
 * 埋点横跨 cdc（不依赖 Spring 上下文之外的模块）与 application（不含基础设施细节）
 * 两个方向不同的模块，公共祖先只有 shared。shared 不引 Spring，
 * 因此用"api 启动时 {@link #install} 注入实例、业务侧静态调用"的方式，
 * 让业务模块只依赖 micrometer-core 这个纯指标库。
 *
 * <p><b>调用约定</b>：
 * <ul>
 *   <li>registry 未注入（未安装/单测环境）时所有调用静默跳过——
 *       指标是旁路能力，绝不能因为它影响主流程；</li>
 *   <li>命名走 micrometer 规范（小写点分），Prometheus 端自动转
 *       {@code iris_cdc_events_total} 形式；</li>
 *   <li>tag 值必须是低基数的（entity/result/stream 这类有限枚举），
 *       禁止把 query、key 这类高基数值塞进 tag——会把注册表撑爆。</li>
 * </ul>
 */
public final class IrisMetrics {

    private static volatile MeterRegistry registry;

    private IrisMetrics() {
    }

    /** 由 api 模块启动时注入 Boot 管理的 MeterRegistry（含 Prometheus 注册表）。 */
    public static void install(MeterRegistry meterRegistry) {
        registry = meterRegistry;
    }

    /** 计数器 +1。tags 形如 {"entity","customer","result","ok"}（key/value 交替）。 */
    public static void increment(String name, String... tags) {
        MeterRegistry r = registry;
        if (r == null) {
            return;
        }
        r.counter(name, tags).increment();
    }

    /**
     * 计数器 +n（n 可为小数，如批处理数量）。
     */
    public static void increment(String name, double amount, String... tags) {
        MeterRegistry r = registry;
        if (r == null) {
            return;
        }
        r.counter(name, tags).increment(amount);
    }

    /**
     * 执行一段代码并记录耗时（Timer，单位秒，Prometheus 端带 _seconds_count/sum/max）。
     * 执行体抛异常也计入耗时（异常路径的耗时同样有价值）；异常原样外抛由调用方处理。
     */
    public static <T> T time(String name, Callable<T> action, String... tags) {
        long start = System.nanoTime();
        try {
            return callUnchecked(action);
        } finally {
            MeterRegistry r = registry;
            if (r != null) {
                // 代码级配置 client 端分位数（actuator /metrics JSON 输出 PERCENTILE_50/95/99）：
                // Spring Boot 4.1 下 management.metrics.distribution.percentiles 配置项不生效，
                // 代码级 publishPercentiles 是跨版本稳定的兜底手段（控制台延迟分位图依赖）
                Timer.builder(name).tags(tags)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(r)
                        .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
        }
    }

    /**
     * 注册 Gauge（当前值型指标，如 stream 积压条数）。
     *
     * @param valueSupplier 取值函数：每次指标抓取时调用，必须是廉价操作（O(1) 命令）
     * @return 注册成功返回 true；重复注册同名同 tag 的 Gauge 会被 micrometer 忽略（幂等）
     */
    public static boolean gauge(String name, Supplier<Double> valueSupplier, String... tags) {
        MeterRegistry r = registry;
        if (r == null) {
            return false;
        }
        List<Tag> tagList = toTags(tags);
        // 幂等：同一 name+tags 已注册过就不重复注册（micrometer 对重复注册会静默忽略，
        // 但 supplier 只会生效第一次——这里显式判断避免误用新 supplier 的困惑）
        if (r.find(name).tags(tagList).gauge() != null) {
            return true;
        }
        Gauge.builder(name, valueSupplier, Supplier::get)
                .tags(tagList)
                .register(r);
        return true;
    }

    private static List<Tag> toTags(String... tags) {
        List<Tag> tagList = new ArrayList<>();
        for (int i = 0; i + 1 < tags.length; i += 2) {
            tagList.add(Tag.of(tags[i], tags[i + 1]));
        }
        return tagList;
    }

    private static <T> T callUnchecked(Callable<T> action) {
        try {
            return action.call();
        } catch (IrisException e) {
            // 直接透传：IrisException 是业务契约错误（错误码稳定），包一层 RuntimeException
            // 会让 GlobalExceptionHandler 判为"未处理异常"返回 IRIS-9999/500，
            // 调用方拿不到本该是 400/404 的语义
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 供需要"耗时但不接管返回值"的场景使用的简单包装（无返回值版 time）。 */
    public static void time(String name, Runnable action, String... tags) {
        long start = System.nanoTime();
        try {
            action.run();
        } finally {
            MeterRegistry r = registry;
            if (r != null) {
                // 与 Callable 版 time 保持一致：client 端分位数
                Timer.builder(name).tags(tags)
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(r)
                        .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
        }
    }

    /**
     * 读取 Timer 的 client 端分位数（供控制台延迟分位展示）。
     *
     * <p><b>为什么不用 actuator /metrics JSON</b>：Micrometer 1.17 起 percentile
     * 值不再从 {@code measure()} 输出（只余 COUNT/TOTAL_TIME/MAX），只能经
     * {@code takeSnapshot()} 获取——最小实验验证过。本方法是 P50/P95/P99 的
     * 稳定读取通道；yml 与代码级 {@code publishPercentiles} 负责让直方图有数据可读。
     *
     * @param tags key/value 交替（如 "entity","customer"）；空 = 不带 tag 查找
     * @return {"p50": 毫秒, "p95": 毫秒, "p99": 毫秒}；Timer 不存在返回空 map
     */
    public static Map<String, Double> timerPercentiles(String name, String... tags) {
        MeterRegistry r = registry;
        if (r == null) {
            return Map.of();
        }
        Timer timer = r.find(name).tags(toTags(tags)).timer();
        if (timer == null) {
            return Map.of();
        }
        return toPercentileMap(timer);
    }

    /**
     * 按指定 tag 分组列出某 Timer 的全部分位（如"P95 per entity"）。
     *
     * @return 每项 {"entity"(=tagKey), "count", "p50", "p95", "p99"}（毫秒）；
     *         指标不存在或没有任何已注册 Timer 返回空 list
     */
    public static List<Map<String, Object>> timerPercentilesByTag(String name, String tagKey) {
        MeterRegistry r = registry;
        if (r == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Timer timer : r.find(name).timers()) {
            String tagValue = timer.getId().getTag(tagKey);
            if (tagValue == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(tagKey, tagValue);
            item.put("count", (long) timer.count());
            item.putAll(toPercentileMap(timer));
            out.add(item);
        }
        return out;
    }

    private static Map<String, Double> toPercentileMap(Timer timer) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (ValueAtPercentile v : timer.takeSnapshot().percentileValues()) {
            out.put("p" + Math.round(v.percentile() * 100),
                    Math.round(v.value(TimeUnit.MILLISECONDS) * 100.0) / 100.0);
        }
        return out;
    }

    /**
     * 读取计数器当前值（供控制台统计端点使用）。
     *
     * <p>计数器不存在（从未递增过）返回 0 而非 null——
     * "还没发生过"在统计语义上就是 0，调用方不必判空。
     */
    public static double counterValue(String name, String... tags) {
        MeterRegistry r = registry;
        if (r == null) {
            return 0;
        }
        var counter = r.find(name).tags(toTags(tags)).counter();
        return counter == null ? 0 : counter.count();
    }
}

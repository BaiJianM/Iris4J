package com.iris4j.api.controller;

import com.iris4j.shared.metrics.IrisMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 查询延迟分位端点（管理控制台）。
 *
 * <p><b>为什么不直接用 actuator /metrics</b>：Micrometer 1.17 起 client 端
 * percentile 值不再出现在 actuator JSON 的 measurements 里，
 * 只能经 {@code takeSnapshot()} 读取——shared 的 IrisMetrics
 * 封装了这条通道，本控制器只做投影。前端"延迟分位/P95 per entity"消费本端点。
 *
 * <p>响应：[{"entity":"customer","count":3,"p50":9.2,"p95":9.2,"p99":9.2}]（毫秒）；
 * 从未执行过查询时返回空数组——前端显示"无数据"而非伪造 0 值。
 */
@RestController
@RequestMapping("/api/v1/metrics")
public class QueryLatencyController {

    /** iris.query Timer 按 entity tag 分组的 P50/P95/P99（毫秒）。 */
    @GetMapping("/query-latency")
    public List<Map<String, Object>> queryLatency() {
        return IrisMetrics.timerPercentilesByTag("iris.query", "entity");
    }
}

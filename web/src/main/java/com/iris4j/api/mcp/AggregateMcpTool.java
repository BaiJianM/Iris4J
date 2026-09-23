package com.iris4j.api.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.iris4j.api.security.AgentIdentity;
import com.iris4j.application.query.EntityQueryService;
import com.iris4j.application.query.IndexReadinessService;
import com.iris4j.context.model.AggregateRequest;
import com.iris4j.context.model.AggregateResult;
import com.iris4j.context.model.RangeFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具入口：{@code aggregate_entity}。
 *
 * <p><b>为什么必须在 MCP 面暴露</b>：MCP 两跳模式（tools/list 25 个）下模型只能
 * 看到注册在 MCP 目录里的工具，没有它「各渠道多少笔」类统计问题会退化回穷举查询。
 * 中间件定位下 MCP 是一等公民入口，工具面必须与直连目录一致。
 *
 * <p><b>共用应用服务的意义</b>（与 {@link EntityQueryMcpTool} 同源）：
 * REST / Agent 直连 / MCP 三入口走完全相同的聚合路径
 * （{@link EntityQueryService} 装饰链：安全 → 缓存守卫 → 校验 → 引擎），
 * 含跨实体维度归并（group_by 支持 {@code fk->维表.维度字段}），
 * 不会出现入口行为不一致。
 *
 * <p><b>聚合不缓存</b>：与 Agent 直连同标准（FT.AGGREGATE 结果依赖维表新鲜度，
 * 版本守卫声明成本高而收益低），每次实时执行。
 */
@Component
public class AggregateMcpTool {

    private static final Logger log = LoggerFactory.getLogger(AggregateMcpTool.class);

    private final EntityQueryService queryService;
    private final IndexReadinessService indexReadinessService;

    public AggregateMcpTool(EntityQueryService queryService,
                            IndexReadinessService indexReadinessService) {
        this.queryService = queryService;
        this.indexReadinessService = indexReadinessService;
    }

    @McpTool(
            name = "aggregate_entity",
            description = "实体聚合统计（GROUPBY 分组 + COUNT/SUM/AVG/MIN/MAX 归约 + TopN 排序），"
                    + "在 Redis 引擎服务端完成，不拉明细，适合「每 X 多少笔/总额」类统计问题。"
                    + "group_by 支持「本实体字段」或跨实体维度路径「fk字段->维表实体.维度字段」"
                    + "（要求 Schema 已声明 relatedEntity 关系；"
                    + "归并时返回 inputGroups=归并前原始组数）。"
                    + "metrics 每项 {op, field}：op ∈ count/sum/avg/min/max，count 不需要 field，"
                    + "其余必须传 numeric 字段；不要传 alias，结果列名固定为 count 或 <op>_<字段名>。"
                    + "sortBy 只能填结果列名或分组字段名。多租户实体必须传 tenant。")
    public Map<String, Object> aggregateEntity(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace,
            @McpToolParam(description = "实体名（须为 Schema 中已注册的实体）", required = true) String entity,
            @McpToolParam(description = "分组字段列表（本实体字段或 fk->维表.维度 路径），可空=全局单组",
                    required = false) List<String> groupBy,
            @McpToolParam(description = "归约指标列表，每项 {op, field}；缺省 = count",
                    required = false) List<MetricDto> metrics,
            @McpToolParam(description = "等值过滤条件（字段名→值），与 query_entity 同语义",
                    required = false) Map<String, Object> filters,
            @McpToolParam(description = "数值范围过滤（字段名→{min,max}），仅 numeric 索引字段有效",
                    required = false) Map<String, RangeDto> rangeFilters,
            @McpToolParam(description = "TEXT 全文匹配（字段名→文本，多词 AND），仅 text 索引字段有效",
                    required = false) Map<String, String> textFilters,
            @McpToolParam(description = "排序依据：结果列名（count/sum_amount）或分组字段名；缺省不排序",
                    required = false) String sortBy,
            @McpToolParam(description = "是否降序（TopN 场景传 true）", required = false) Boolean sortDesc,
            @McpToolParam(description = "返回组数上限（TopN 的 N），默认 20，上限 100",
                    required = false) Integer limit,
            @McpToolParam(description = "租户 ID；多租户实体必填，非多租户实体可省略",
                    required = false) String tenant) {

        AgentIdentity agent = McpRequestIdentity.resolve();
        Map<String, RangeFilter> ranges = null;
        if (rangeFilters != null) {
            Map<String, RangeFilter> converted = new LinkedHashMap<>();
            rangeFilters.forEach((k, v) -> converted.put(k, RangeFilter.parse(v.min(), v.max())));
            ranges = converted;
        }
        List<AggregateRequest.Metric> metricList = null;
        if (metrics != null) {
            metricList = metrics.stream()
                    .map(m -> new AggregateRequest.Metric(m.op(), m.field()))
                    .toList();
        }
        AggregateRequest request = new AggregateRequest(
                namespace,
                entity,
                groupBy,
                List.of(), // dimensionPaths：由 record 构造器按 "fk->entity.dim" 语法自动分流
                metricList,
                filters,
                tenant,
                agent == null ? null : agent.tags(),
                ranges,
                textFilters,
                sortBy,
                sortDesc != null && sortDesc,
                limit == null ? 20 : limit);
        log.debug("MCP 聚合请求 ns={} entity={} groupBy={} metrics={}",
                namespace, entity, groupBy, metrics);
        AggregateResult result = queryService.aggregate(request);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entity", entity);
        out.put("totalGroups", result.totalGroups());
        if (result.inputGroups() != result.totalGroups()) {
            // 维度归并时声明标准：如 2957 个配置实例归并成 8 类，调用方需要这两个数
            out.put("inputGroups", result.inputGroups());
        }
        out.put("rows", result.rows());
        String hint = indexReadinessService.readinessHint(namespace, entity);
        if (hint != null) {
            out.put("notice", hint);
        }
        return out;
    }

    /**
     * 归约指标参数 DTO：op ∈ count/sum/avg/min/max；count 可不传 field，其余必传。
     *
     * <p>{@code field} 标 {@code @JsonProperty(required = false)}：Spring AI 的
     * {@code JsonSchemaGenerator} 经 victools JacksonSchemaModule
     * （RESPECT_JSONPROPERTY_REQUIRED）生成嵌套 DTO schema，借此把 field 移出
     * required 数组——否则 {"op":"count"} 会在 MCP 入口被 schema 校验拦下，
     * 与工具描述「count 不需要 field」自相矛盾。
     */
    public record MetricDto(String op,
                            @JsonProperty(required = false) String field) {
    }

    /** 范围条件参数 DTO：与 {@link EntityQueryMcpTool.RangeDto} 同构（独立定义避免跨工具耦合）。
     *  端点 Object：数字毫秒或日期字面量字符串均可（{@link RangeFilter#parse} 统一换算）。 */
    public record RangeDto(Object min, Object max) {
    }
}

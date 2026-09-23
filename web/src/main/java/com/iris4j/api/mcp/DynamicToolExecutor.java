package com.iris4j.api.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris4j.api.security.AgentIdentity;
import com.iris4j.application.query.EntityQueryService;
import com.iris4j.application.query.IndexReadinessService;
import com.iris4j.application.query.ResultTrimmer;
import com.iris4j.context.model.QueryRequest;
import com.iris4j.context.model.RangeFilter;
import com.iris4j.shared.model.Page;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 动态实体工具的<b>执行器</b>（从 {@link DynamicEntityToolRegistrar} 抽取）。
 *
 * <p><b>职责</b>：ToolDef → MCP 工具规格（元数据 + 回调）与 ToolDef + 参数 →
 * 查询执行。all 模式（直注册）与 on-demand 模式（{@code call_entity_tool} 分发）
 * 共用同一条执行路径——身份解析、参数转换、查询服务、错误语义完全一致。
 *
 * <p><b>身份注入</b>：回调里从 request attribute 取 AgentIdentity（MCP 与 REST
 * 共用 X-API-Key 鉴权），agentTags 进入查询链做行级/字段级裁剪——
 * 与 REST 完全同语义；无身份时 fail-closed（由查询链处理，本层不显式报错）。
 */
@Component
public class DynamicToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(DynamicToolExecutor.class);

    private final EntityToolFactory factory;
    private final EntityQueryService queryService;
    private final IndexReadinessService indexReadinessService;
    private final ObjectMapper objectMapper;

    public DynamicToolExecutor(EntityToolFactory factory,
                               EntityQueryService queryService,
                               IndexReadinessService indexReadinessService,
                               ObjectMapper objectMapper) {
        this.factory = factory;
        this.queryService = queryService;
        this.indexReadinessService = indexReadinessService;
        this.objectMapper = objectMapper;
    }

    /**
     * 构建工具规格（元数据 + 执行回调）。
     *
     * <p><b>回调里 catch 所有异常并返回 isError=true</b>：MCP 工具抛异常会导致
     * 协议层错误，Agent 拿到的是"工具调用失败"而非可读的原因。
     * 转成 isError + 文本消息，Agent 能读到"查询失败: 字段不存在: xxx"并自我纠正。
     */
    public McpServerFeatures.SyncToolSpecification toSpec(EntityToolFactory.ToolDef def) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(def.name())
                .description(factory.buildDescription(def))
                .inputSchema(McpSchema.JsonSchema.builder()
                        .type("object")
                        .properties(factory.inputProperties(def))
                        .build())
                .build();
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) ->
                execute(def, request.arguments()));
    }

    /**
     * 执行一次动态工具调用（all 模式回调与 on-demand 的 call_entity_tool 共用）。
     * 身份 fail-closed、参数类型兜底、错误转 isError+文本——语义与直调完全一致。
     */
    public McpSchema.CallToolResult execute(EntityToolFactory.ToolDef def, Map<String, Object> rawArgs) {
        try {
            AgentIdentity agent = McpRequestIdentity.resolve();
            Page<Map<String, Object>> page = queryService.query(
                    toQueryRequest(def, rawArgs, agent));
            // 结果硬裁剪：50 行/32KB 双闸门，
            // MCP 与 Agent 直连路径同一标准（AgentToolDispatcher 同款）
            ResultTrimmer.Trimmed trimmed = ResultTrimmer.trim(
                    page.items(), page.total(), objectMapper);
            // 索引回填期提示：回填中查询已降级 SCAN 保正确性，结果可能不完整。
            // 显式透出给模型，避免空结果被误读为"数据不存在"（否则 Agent 会推理死循环）
            String hint = indexReadinessService.readinessHint(def.schema().namespace(), def.schema().entity());
            if (hint == null && trimmed.notice() == null) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent(objectMapper.writeValueAsString(page))
                        .build();
            }
            Map<String, Object> withNotice = new LinkedHashMap<>();
            withNotice.put("items", trimmed.items());
            withNotice.put("total", page.total());
            withNotice.put("page", page.page());
            withNotice.put("pageSize", page.pageSize());
            if (trimmed.notice() != null || hint != null) {
                // 两条提示可能同时发生（截断 + 回填中），拼接共存不可互相覆盖——
                // 覆盖会让模型把残缺页当完整结果（裁剪通知要防的场景）
                List<String> notices = new ArrayList<>(2);
                if (hint != null) {
                    notices.add(hint);
                }
                if (trimmed.notice() != null) {
                    notices.add(trimmed.notice());
                }
                withNotice.put("notice", String.join(" ", notices));
            }
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(withNotice))
                    .build();
        } catch (Exception e) {
            log.debug("动态工具调用失败 tool={}: {}", def.name(), e.getMessage(), e);
            return McpSchema.CallToolResult.builder()
                    .addTextContent("查询失败: " + e.getMessage())
                    .isError(true)
                    .build();
        }
    }

    /**
     * MCP 请求参数 -> {@link QueryRequest}（按工具 kind 分派）。
     *
     * <p><b>类型检查用 {@code instanceof} 模式匹配而非强转</b>：
     * MCP 传来的 arguments 是 {@code Map<String,Object>}，
     * JSON 数字可能反序列化成 Integer/Long/Double，
     * 直接强转会 ClassCastException；统一用 instanceof Number 兜住各种数字类型。
     */
    @SuppressWarnings("unchecked")
    QueryRequest toQueryRequest(EntityToolFactory.ToolDef def, Map<String, Object> rawArgs, AgentIdentity agent) {
        Map<String, Object> args = rawArgs == null ? Map.of() : rawArgs;
        var schema = def.schema();
        List<String> agentTags = agent == null ? null : agent.tags();
        return switch (def.kind()) {
            case "query" -> {
                List<String> fields = args.get("fields") instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList() : null;
                Map<String, Object> filters = args.get("filters") instanceof Map<?, ?> map
                        ? (Map<String, Object>) map : null;
                Map<String, RangeFilter> ranges = parseRangeFilters(args.get("rangeFilters"));
                Map<String, String> texts = args.get("textFilters") instanceof Map<?, ?> map
                        ? ((Map<String, Object>) map).entrySet().stream()
                                .filter(e -> e.getValue() != null)
                                .collect(Collectors.toMap(Map.Entry::getKey,
                                        e -> String.valueOf(e.getValue()),
                                        (a, b) -> a, LinkedHashMap::new))
                        : null;
                Integer page = args.get("page") instanceof Number n ? n.intValue() : 1;
                Integer pageSize = args.get("pageSize") instanceof Number n ? n.intValue() : 20;
                String tenant = args.get("tenant") instanceof String s && !s.isBlank() ? s : null;
                String sortField = args.get("sortField") instanceof String s && !s.isBlank() ? s : null;
                boolean sortDesc = args.get("sortDesc") instanceof Boolean b && b;
                yield new QueryRequest(schema.namespace(), schema.entity(), fields, filters,
                        page, pageSize, tenant, agentTags, ranges, texts, sortField, sortDesc);
            }
            case "get", "filter" -> {
                // Map.of 不允许 null 值：漏传主键/过滤值时先给可读错误，
                // 否则 NPE message=null，Agent 只能看到"查询失败: null"
                Object value = args.get(def.field().name());
                if (value == null) {
                    throw new IllegalArgumentException("缺少参数: " + def.field().name());
                }
                yield new QueryRequest(schema.namespace(), schema.entity(), null,
                        Map.of(def.field().name(), value),
                        1, 20, argTenant(args), agentTags, null, null);
            }
            case "range" -> {
                // parse 接受数字毫秒或日期字面量字符串（按 UTC 字面换算）
                RangeFilter range = RangeFilter.parse(args.get("min"), args.get("max"));
                yield new QueryRequest(schema.namespace(), schema.entity(), null, null,
                        1, 20, argTenant(args), agentTags,
                        Map.of(def.field().name(), range), null);
            }
            case "search" -> {
                // 缺 query 时不能 String.valueOf 兜成字面量 "null" 去做全文匹配（返回噪声结果）
                Object query = args.get("query");
                if (query == null || String.valueOf(query).isBlank()) {
                    throw new IllegalArgumentException("缺少参数: query");
                }
                yield new QueryRequest(schema.namespace(), schema.entity(), null, null,
                        1, 20, argTenant(args), agentTags, null,
                        Map.of(def.field().name(), String.valueOf(query)));
            }
            default -> new QueryRequest(schema.namespace(), schema.entity(), null, null,
                    1, 20, argTenant(args), agentTags, null, null);
        };
    }

    /** 语义化工具的 tenant 参数（多租户实体必填，缺省报错由查询链给出可读信息）。 */
    private String argTenant(Map<String, Object> args) {
        return args.get("tenant") instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * 解析通用工具的 rangeFilters 参数：{@code {字段名: {min, max}}}。
     * 端点接受数字毫秒或日期字面量字符串（{@link RangeFilter#parse} 统一换算）。
     * 结构不对/全空端点的条件丢弃（等值场景应走 filters）。
     */
    private Map<String, RangeFilter> parseRangeFilters(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        Map<String, RangeFilter> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> bound)) {
                continue;
            }
            Object min = bound.get("min");
            Object max = bound.get("max");
            if (min != null || max != null) {
                result.put(String.valueOf(e.getKey()), RangeFilter.parse(min, max));
            }
        }
        return result.isEmpty() ? null : result;
    }
}

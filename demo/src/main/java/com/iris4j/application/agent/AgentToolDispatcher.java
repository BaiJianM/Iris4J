package com.iris4j.application.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris4j.application.ops.CdcInsightService;
import com.iris4j.application.ops.DlqAdminService;
import com.iris4j.application.query.EntityQueryService;
import com.iris4j.application.query.IndexReadinessService;
import com.iris4j.application.cache.LlmCacheService;
import com.iris4j.application.memory.MemoryService;
import com.iris4j.application.ops.RedisOpsService;
import com.iris4j.application.ops.RedisOpsSnapshot;
import com.iris4j.application.query.RelatedEntityService;
import com.iris4j.application.query.ResultTrimmer;
import com.iris4j.context.model.AggregateRequest;
import com.iris4j.context.model.AggregateResult;
import com.iris4j.context.model.QueryRequest;
import com.iris4j.context.model.RangeFilter;
import com.iris4j.context.schema.EntitySchema;
import com.iris4j.context.schema.SchemaManager;
import com.iris4j.shared.error.ErrorCode;
import com.iris4j.shared.error.IrisException;
import com.iris4j.memory.LongTermMemory;
import com.iris4j.memory.SearchMode;
import com.iris4j.shared.error.ErrorCode;
import com.iris4j.shared.error.IrisException;
import com.iris4j.shared.model.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 工具分发器：按工具名分发到与 MCP 工具薄壳相同的 application 服务。
 *
 * <p><b>一致性红线</b>：query_entity / get_related_entities 走装配好的
 * {@link EntityQueryService} 链头（access-controlled → semantic → exact → default），
 * 与 MCP 动态工具的执行路径逐字节一致——租户隔离、访问 tags 裁剪、版本守卫、
 * 缓存装饰全部自动生效。Agent 不是绕过治理的第二条通路。
 *
 * <p><b>错误约定</b>：业务异常（IRIS-1xxx，如实体不存在/租户缺失）转成
 * {@code {error, message}} JSON 返回给模型——Agent 能读到错误并自行修正参数；
 * 只有基础设施级异常才向上抛（由编排层转 SSE error 事件）。
 *
 * <p><b>依赖收集</b>：数据查询类工具返回 entitiesTouched（本调用触及的实体名），
 * 编排层聚合后作为 LLM 语义缓存条目的 dependencies（版本守卫）。
 */
@Component
public class AgentToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AgentToolDispatcher.class);

    private final EntityQueryService queryService;
    private final RelatedEntityService relatedEntityService;
    private final MemoryService memoryService;
    private final LlmCacheService llmCacheService;
    private final RedisOpsService redisOpsService;
    private final CdcInsightService cdcInsightService;
    private final DlqAdminService dlqAdminService;
    private final SchemaManager schemaManager;
    private final IndexReadinessService indexReadinessService;
    private final ObjectMapper objectMapper;

    public AgentToolDispatcher(EntityQueryService queryService,
                               RelatedEntityService relatedEntityService,
                               MemoryService memoryService,
                               LlmCacheService llmCacheService,
                               RedisOpsService redisOpsService,
                               CdcInsightService cdcInsightService,
                               DlqAdminService dlqAdminService,
                               SchemaManager schemaManager,
                               IndexReadinessService indexReadinessService,
                               ObjectMapper objectMapper) {
        this.queryService = queryService;
        this.relatedEntityService = relatedEntityService;
        this.memoryService = memoryService;
        this.llmCacheService = llmCacheService;
        this.redisOpsService = redisOpsService;
        this.cdcInsightService = cdcInsightService;
        this.dlqAdminService = dlqAdminService;
        this.schemaManager = schemaManager;
        this.indexReadinessService = indexReadinessService;
        this.objectMapper = objectMapper;
    }

    /**
     * 工具执行上下文：身份与租户信息由编排层从鉴权层透传，Agent 参数不可伪造。
     *
     * @param fresh 时效旁路标志：编排层对用户问题做时效词检测后设置，
     *              true 时数据查询请求带 fresh 标记穿透两层查询缓存强制现算
     */
    public record ToolContext(String namespace, String sessionId, List<String> agentTags, boolean fresh) {

        /** 兼容构造器：无时效旁路形态（fresh=false）。 */
        public ToolContext(String namespace, String sessionId, List<String> agentTags) {
            this(namespace, sessionId, agentTags, false);
        }
    }

    /** 执行结果：resultJson 喂回模型；entitiesTouched 供版本守卫声明依赖。 */
    public record DispatchResult(String resultJson, Set<String> entitiesTouched) {
    }

    /**
     * 执行一次工具调用。业务与基础设施异常都在本方法内转为
     * {@code {error:true,...}} 结果 JSON（Agent 可读并自行修正参数），
     * 不向上抛——编排层只处理 SSE/LLM 通道级失败。
     */
    public DispatchResult dispatch(String tool, String argumentsJson, ToolContext ctx) {
        JsonNode args;
        try {
            args = argumentsJson == null || argumentsJson.isBlank()
                    ? objectMapper.createObjectNode() : objectMapper.readTree(argumentsJson);
        } catch (Exception e) {
            return error("参数不是合法 JSON: " + e.getMessage(), Set.of());
        }
        try {
            return switch (tool) {
                case "query_entity" -> queryEntity(args, ctx);
                case "aggregate_entity" -> aggregateEntity(args, ctx);
                case "get_related_entities" -> related(args, ctx);
                case "get_schema" -> getSchema(args, ctx);
                case "search_long_term_memory" -> searchLongTerm(args, ctx);
                case "save_long_term_memory" -> saveLongTerm(args, ctx);
                case "get_working_memory" -> getWorking(args, ctx);
                case "save_working_memory" -> saveWorking(args, ctx);
                case "search_working_memory" -> searchWorking(args, ctx);
                case "llm_cache_stats" -> plain(llmCacheService.stats(ctx.namespace()));
                case "redis_status" -> plain(redisOpsService.snapshot());
                case "cdc_sources" -> plain(cdcInsightService.sources());
                case "dlq_list" -> dlqList(args);
                default -> error("未知工具: " + tool, Set.of());
            };
        } catch (IrisException e) {
            // 业务异常转 JSON：Agent 可读错误并自行修正（如补 tenant、改字段名）
            log.debug("Agent 工具业务异常 tool={} code={} msg={}", tool, e.errorCode(), e.getMessage(), e);
            return error("[" + e.errorCode() + "] " + e.getMessage(), Set.of());
        } catch (IllegalArgumentException e) {
            return error(e.getMessage(), Set.of());
        } catch (Exception e) {
            // 基础设施级异常（序列化故障等）：同样转 JSON 但打 warn 便于排查
            log.warn("Agent 工具执行异常 tool={}", tool, e);
            return error("工具执行失败: " + e.getMessage(), Set.of());
        }
    }

    // ---------- 数据查询 ----------

    private DispatchResult queryEntity(JsonNode args, ToolContext ctx) throws Exception {
        String entity = text(args, "entity");
        String tenant = text(args, "tenant");
        Map<String, Object> filters = args.hasNonNull("filters")
                ? objectMapper.convertValue(args.get("filters"), new TypeReference<Map<String, Object>>() {
        }) : Map.of();
        Map<String, RangeFilter> ranges = new LinkedHashMap<>();
        if (args.hasNonNull("range_filters")) {
            args.get("range_filters").fields().forEachRemaining(e -> {
                JsonNode n = e.getValue();
                ranges.put(e.getKey(), RangeFilter.parse(
                        rangeEndpoint(n.path("min"), false),
                        rangeEndpoint(n.path("max"), true)));
            });
        }
        Map<String, String> texts = new LinkedHashMap<>();
        if (args.hasNonNull("text_filters")) {
            args.get("text_filters").fields().forEachRemaining(
                    e -> texts.put(e.getKey(), e.getValue().asText()));
        }
        List<String> fields = args.path("fields").isArray()
                ? objectMapper.convertValue(args.get("fields"), new TypeReference<List<String>>() {
        }) : List.of();
        int page = args.path("page").asInt(1);
        int pageSize = args.path("page_size").asInt(20);
        String sortField = args.hasNonNull("sort_field") ? args.get("sort_field").asText() : null;
        boolean sortDesc = args.path("sort_desc").asBoolean(false);

        QueryRequest request = new QueryRequest(ctx.namespace(), entity, fields, filters,
                page, pageSize, tenant, ctx.agentTags(), ranges, texts, sortField, sortDesc,
                ctx.fresh());
        Page<Map<String, Object>> result = queryService.query(request);
        // 结果硬裁剪：50 行/32KB 双闸门，防止整页 JSON 挤干推理空间
        ResultTrimmer.Trimmed trimmed = ResultTrimmer.trim(result.items(), result.total(), objectMapper);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entity", entity);
        out.put("total", result.total());
        out.put("page", result.page());
        out.put("pageSize", result.pageSize());
        out.put("items", trimmed.items());
        // 索引回填期提示：回填中查询已降级 SCAN 保正确性，但结果可能不完整。
        // 必须显式告知模型，否则空结果会被误读为"数据不存在"，与历史对话矛盾时死循环。
        // 截断与回填提示可能同时发生：拼接共存，不能同名 key 互相覆盖
        String hint = indexReadinessService.readinessHint(ctx.namespace(), entity);
        if (trimmed.notice() != null && hint != null) {
            out.put("notice", hint + " " + trimmed.notice());
        } else if (trimmed.notice() != null) {
            out.put("notice", trimmed.notice());
        } else if (hint != null) {
            out.put("notice", hint);
        }
        // 空结果也声明依赖：数据从无到有是典型的 stale 场景（旧答案"查无数据"在
        // 数据灌入后即错误，守卫必须拦截）
        return new DispatchResult(objectMapper.writeValueAsString(out), Set.of(entity));
    }

    /**
     * 服务端聚合：GROUPBY + COUNT/SUM/AVG/MIN/MAX + TopN。
     * 走与 query_entity 完全相同的 {@link EntityQueryService} 装饰链链头——
     * 租户隔离、访问 tags 行级/字段级控制全部生效，聚合不是绕过治理的通路。
     */
    /**
     * range 过滤单端点规范化：数字毫秒直取、日期字符串原样传给
     * {@link RangeFilter#parse}（内部按 UTC 字面换算——LLM 心算 epoch 毫秒会偏移窗口，
     * 日期字面量是首选形态）、
     * 其他形态报 INVALID_QUERY 让模型修正重试。
     */
    private static Object rangeEndpoint(JsonNode v, boolean maxEndpoint) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isTextual()) {
            return v.asText();
        }
        throw new IrisException(ErrorCode.INVALID_QUERY,
                "范围过滤端点应为数字毫秒或日期字符串: " + v);
    }

    private DispatchResult aggregateEntity(JsonNode args, ToolContext ctx) throws Exception {
        String entity = text(args, "entity");
        String tenant = text(args, "tenant");
        List<String> groupBy = args.path("group_by").isArray()
                ? objectMapper.convertValue(args.get("group_by"), new TypeReference<List<String>>() {
        }) : List.of();
        List<AggregateRequest.Metric> metrics = new ArrayList<>();
        if (args.path("metrics").isArray()) {
            for (JsonNode m : args.get("metrics")) {
                metrics.add(new AggregateRequest.Metric(
                        m.path("op").asText(null),
                        m.hasNonNull("field") ? m.get("field").asText() : null));
            }
        }
        Map<String, Object> filters = args.hasNonNull("filters")
                ? objectMapper.convertValue(args.get("filters"), new TypeReference<Map<String, Object>>() {
        }) : Map.of();
        Map<String, RangeFilter> ranges = new LinkedHashMap<>();
        if (args.hasNonNull("range_filters")) {
            args.get("range_filters").fields().forEachRemaining(e -> {
                JsonNode n = e.getValue();
                ranges.put(e.getKey(), RangeFilter.parse(
                        rangeEndpoint(n.path("min"), false),
                        rangeEndpoint(n.path("max"), true)));
            });
        }
        Map<String, String> texts = new LinkedHashMap<>();
        if (args.hasNonNull("text_filters")) {
            args.get("text_filters").fields().forEachRemaining(
                    e -> texts.put(e.getKey(), e.getValue().asText()));
        }
        String sortBy = args.hasNonNull("sort_by") ? args.get("sort_by").asText() : null;
        boolean sortDesc = args.path("sort_desc").asBoolean(false);
        int limit = args.path("limit").asInt(20);

        AggregateRequest request = new AggregateRequest(ctx.namespace(), entity, groupBy,
                List.of(), metrics, filters, tenant, ctx.agentTags(), ranges, texts,
                sortBy, sortDesc, limit);
        AggregateResult result = queryService.aggregate(request);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entity", entity);
        out.put("totalGroups", result.totalGroups());
        if (result.inputGroups() != result.totalGroups()) {
            // 维度归并时声明标准：2957 个配置实例归并成 8 类，模型需要这两个数
            out.put("inputGroups", result.inputGroups());
        }
        out.put("rows", result.rows());
        String hint = indexReadinessService.readinessHint(ctx.namespace(), entity);
        if (hint != null) {
            out.put("notice", hint);
        }
        // 空组也声明依赖：数据从无到有是典型 stale 场景（守卫标准，与 query 一致）。
        // 维度归并：维表实体（via）一并声明——维表变更必须令归并结果失效
        Set<String> touched = new HashSet<>(Set.of(entity));
        for (String g : groupBy) {
            int arrow = g.indexOf("->");
            if (arrow > 0) {
                String via = g.substring(arrow + 2);
                int dot = via.indexOf('.');
                if (dot > 0) {
                    touched.add(via.substring(0, dot));
                }
            }
        }
        return new DispatchResult(objectMapper.writeValueAsString(out), touched);
    }

    private DispatchResult related(JsonNode args, ToolContext ctx) throws Exception {
        String entity = text(args, "entity");
        String id = text(args, "id");
        String field = text(args, "field");
        String tenant = text(args, "tenant");
        int page = args.path("page").asInt(1);
        int pageSize = args.path("page_size").asInt(20);
        Map<String, Object> result = relatedEntityService.related(
                ctx.namespace(), entity, id, field, tenant, ctx.agentTags(), page, pageSize);
        // 触及实体 = 源实体 + 全部关系的目标实体（供版本守卫声明依赖）
        Set<String> touched = new HashSet<>();
        touched.add(entity);
        Object relations = result.get("relations");
        if (relations instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> rel && rel.get("relatedEntity") != null) {
                    touched.add(String.valueOf(rel.get("relatedEntity")));
                }
            }
        }
        return new DispatchResult(objectMapper.writeValueAsString(result), touched);
    }

    private DispatchResult getSchema(JsonNode args, ToolContext ctx) throws Exception {
        String entity = text(args, "entity");
        if (entity != null) {
            EntitySchema s = schemaManager.get(ctx.namespace(), entity);
            return plain(schemaSummary(s));
        }
        List<Map<String, Object>> all = schemaManager.list().stream()
                .filter(s -> s.namespace().equals(ctx.namespace()))
                .map(this::schemaSummary)
                .toList();
        return plain(Map.of("namespace", ctx.namespace(), "entities", all));
    }

    private Map<String, Object> schemaSummary(EntitySchema s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entity", s.entity());
        if (s.description() != null) {
            m.put("description", s.description());
        }
        m.put("primaryKeys", s.primaryKeys());
        m.put("tenantField", s.tenantField());
        List<Map<String, Object>> fields = new ArrayList<>();
        s.fields().forEach(f -> {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("name", f.name());
            fm.put("type", String.valueOf(f.type()));
            if (f.index() != null) {
                fm.put("index", String.valueOf(f.index()));
            }
            if (f.relatedEntity() != null) {
                fm.put("relatedEntity", f.relatedEntity());
            }
            if (f.description() != null) {
                fm.put("description", f.description());
            }
            // 值域语义：枚举/状态类字段各取值的含义（如 0=未支付; 1=已支付）。
            // 聚合标准类问题（营收只算已支付）的正答前提，模型第二跳即可见
            if (f.values() != null) {
                fm.put("values", f.values());
            }
            fields.add(fm);
        });
        m.put("fields", fields);
        return m;
    }

    // ---------- 记忆 ----------

    private DispatchResult searchLongTerm(JsonNode args, ToolContext ctx) throws Exception {
        String query = text(args, "query");
        int limit = args.path("limit").asInt(5);
        SearchMode mode = parseMode(args.path("mode").asText(null));
        String owner = text(args, "owner");
        List<LongTermMemory> result = owner != null
                ? memoryService.searchLongTermMemory(ctx.namespace(), owner, query, limit, mode)
                : memoryService.searchLongTermMemory(ctx.namespace(), query, limit, mode);
        return plain(Map.of("total", result.size(), "items", result));
    }

    private DispatchResult saveLongTerm(JsonNode args, ToolContext ctx) throws Exception {
        String content = text(args, "content");
        String type = textOr(args, "type", "fact");
        String owner = text(args, "owner");
        LongTermMemory saved = owner != null
                ? memoryService.saveLongTermMemory(ctx.namespace(), type, content, owner)
                : memoryService.saveLongTermMemory(ctx.namespace(), type, content);
        return plain(saved);
    }

    private DispatchResult getWorking(JsonNode args, ToolContext ctx) throws Exception {
        String sessionId = textOr(args, "session_id", ctx.sessionId());
        return plain(memoryService.getWorkingMemory(ctx.namespace(), sessionId));
    }

    private DispatchResult saveWorking(JsonNode args, ToolContext ctx) throws Exception {
        String content = text(args, "content");
        String sessionId = textOr(args, "session_id", ctx.sessionId());
        memoryService.saveWorkingMemory(ctx.namespace(), sessionId, content);
        return plain(Map.of("saved", true, "sessionId", sessionId));
    }

    private DispatchResult searchWorking(JsonNode args, ToolContext ctx) throws Exception {
        String query = text(args, "query");
        int limit = args.path("limit").asInt(5);
        String sessionId = textOr(args, "session_id", ctx.sessionId());
        return plain(memoryService.searchWorkingMemory(ctx.namespace(), sessionId, query, limit));
    }

    // ---------- 运维观测 ----------

    private DispatchResult dlqList(JsonNode args) throws Exception {
        int limit = args.path("limit").asInt(20);
        return plain(dlqAdminService.list(null, null, limit));
    }

    // ---------- 辅助 ----------

    private SearchMode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return SearchMode.HYBRID;
        }
        return SearchMode.valueOf(mode.trim().toUpperCase());
    }

    private String text(JsonNode args, String field) {
        JsonNode n = args.get(field);
        return n == null || n.isNull() || n.asText().isBlank() ? null : n.asText();
    }

    private String textOr(JsonNode args, String field, String fallback) {
        String v = text(args, field);
        return v != null ? v : fallback;
    }

    private DispatchResult plain(Object value) throws Exception {
        return new DispatchResult(objectMapper.writeValueAsString(value), Set.of());
    }

    private DispatchResult error(String message, Set<String> touched) {
        try {
            return new DispatchResult(objectMapper.writeValueAsString(
                    Map.of("error", true, "message", message)), touched);
        } catch (Exception e) {
            return new DispatchResult("{\"error\":true}", touched);
        }
    }
}

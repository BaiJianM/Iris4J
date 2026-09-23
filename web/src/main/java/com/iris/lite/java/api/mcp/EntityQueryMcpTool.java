package com.iris.lite.java.api.mcp;

import com.iris.lite.java.api.security.AgentIdentity;
import com.iris.lite.java.application.query.EntityQueryService;
import com.iris.lite.java.application.query.IndexReadinessService;
import com.iris.lite.java.application.query.ResultTrimmer;
import com.iris.lite.java.context.model.QueryRequest;
import com.iris.lite.java.context.model.RangeFilter;
import com.iris.lite.java.shared.model.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具入口，与 REST 共用同一个 {@link EntityQueryService}。
 *
 * <p>通过 Spring AI MCP Server（Streamable-HTTP）暴露 {@code query_entity} 工具，
 * 供任意 MCP 客户端（Claude / Cursor / MCP Inspector 等）调用。
 *
 * <p><b>共用应用服务的意义</b>：REST 与 MCP 走完全相同的查询路径
 * （含缓存、租户隔离、字段校验），不会出现"两个入口行为不一致"的问题。
 *
 * <p><b>身份注入（与 REST 一致）</b>：agentTags 从 request attribute 取
 * （{@code ApiKeyAuthFilter} 对 /mcp 同样做了 X-API-Key 校验），
 * 调用方无法伪造；无身份时对行级保护实体 fail-closed。
 */
@Component
public class EntityQueryMcpTool {

    private static final Logger log = LoggerFactory.getLogger(EntityQueryMcpTool.class);

    private final EntityQueryService queryService;
    private final IndexReadinessService indexReadinessService;
    private final ObjectMapper objectMapper;

    public EntityQueryMcpTool(EntityQueryService queryService,
                              IndexReadinessService indexReadinessService,
                              ObjectMapper objectMapper) {
        this.queryService = queryService;
        this.indexReadinessService = indexReadinessService;
        this.objectMapper = objectMapper;
    }

    /**
     * 通用实体查询工具（静态，与动态生成的语义化工具并存）。
     *
     * <p>静态工具适合"先探索有哪些实体"的场景；
     * 确定要查哪个实体时，动态工具（get_customer_by_id 等）更省事。
     */
    @McpTool(
            name = "query_entity",
            description = "查询实体数据。支持主键精确查询（如 filters 传 {id:1001}）、"
                    + "字段等值过滤（如 {level:1}）、数值范围过滤（rangeFilters，如 {price:{min:10,max:100}}）、"
                    + "全文匹配（textFilters，仅 text 索引字段）、字段裁剪与分页，"
                    + "以及任意索引字段排序（sortField+sortDesc）。"
                    + "多租户实体必须传 tenant。返回命中的实体字段映射列表。")
    public Map<String, Object> queryEntity(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace,
            @McpToolParam(description = "实体名（须为 Schema 中已注册的实体）", required = true) String entity,
            @McpToolParam(description = "要返回的字段列表，为空返回全部字段", required = false) List<String> fields,
            @McpToolParam(description = "等值过滤条件（字段名→值），如 id=1001 或 level=1，可省略", required = false) Map<String, Object> filters,
            @McpToolParam(description = "数值范围过滤（字段名→{min,max}，端点可缺省），仅 numeric 索引字段有效", required = false) Map<String, RangeDto> rangeFilters,
            @McpToolParam(description = "全文匹配（字段名→文本，多词 AND），仅 text 索引字段有效", required = false) Map<String, String> textFilters,
            @McpToolParam(description = "页码，从 1 开始，默认 1", required = false) Integer page,
            @McpToolParam(description = "每页大小，默认 20，上限 200", required = false) Integer pageSize,
            @McpToolParam(description = "租户 ID，如 t1；多租户实体必填，非多租户实体可省略", required = false) String tenant,
            @McpToolParam(description = "排序字段（索引字段），如 total_amount；缺省按主键升序", required = false) String sortField,
            @McpToolParam(description = "是否降序，配合 sortField 使用", required = false) Boolean sortDesc) {

        // 身份来自鉴权过滤器的 request attribute（MCP 与 REST 同一入口，调用方伪造不了）
        AgentIdentity agent = McpRequestIdentity.resolve();
        Map<String, RangeFilter> ranges = null;
        if (rangeFilters != null) {
            Map<String, RangeFilter> converted = new LinkedHashMap<>();
            rangeFilters.forEach((k, v) -> converted.put(k, RangeFilter.parse(v.min(), v.max())));
            ranges = converted;
        }
        QueryRequest request = new QueryRequest(
                namespace,
                entity,
                fields,
                filters,
                page == null ? 1 : page,
                pageSize == null ? 20 : pageSize,
                tenant,
                agent == null ? null : agent.tags(),
                ranges,
                textFilters,
                sortField,
                sortDesc != null && sortDesc);
        log.debug("MCP 查询请求 ns={} entity={} filters={} range={} text={} agent={}",
                namespace, entity, filters, ranges, textFilters,
                agent == null ? "-" : agent.agentId());
        Page<Map<String, Object>> resultPage = queryService.query(request);
        // 结果硬裁剪：与 DynamicToolExecutor/AgentToolDispatcher 同一标准（50 行/32KB），
        // 否则静态 query_entity 的 200 行宽行 payload 无上界直接灌进 LLM 上下文
        ResultTrimmer.Trimmed trimmed = ResultTrimmer.trim(
                resultPage.items(), resultPage.total(), objectMapper);
        // 索引回填期提示：回填中查询已降级 SCAN 保正确性，结果可能不完整。
        // 显式透出给模型，避免空结果被误读为"数据不存在"（否则 Agent 会推理死循环）
        String hint = indexReadinessService.readinessHint(namespace, entity);
        if (hint == null && trimmed.notice() == null) {
            Map<String, Object> plain = new LinkedHashMap<>();
            plain.put("items", resultPage.items());
            plain.put("total", resultPage.total());
            plain.put("page", resultPage.page());
            plain.put("pageSize", resultPage.pageSize());
            return plain;
        }
        Map<String, Object> withNotice = new LinkedHashMap<>();
        withNotice.put("items", trimmed.items());
        withNotice.put("total", resultPage.total());
        withNotice.put("page", resultPage.page());
        withNotice.put("pageSize", resultPage.pageSize());
        // 截断与回填提示可能同时发生，拼接共存不可互相覆盖
        List<String> notices = new ArrayList<>(2);
        if (hint != null) {
            notices.add(hint);
        }
        if (trimmed.notice() != null) {
            notices.add(trimmed.notice());
        }
        withNotice.put("notice", String.join(" ", notices));
        return withNotice;
    }

    /**
     * 范围条件参数 DTO：min/max 均可缺省（null = 开放端点）。
     * 端点用 Object：AI 调用方传数字毫秒或日期字面量字符串均可
     * （{@link RangeFilter#parse} 统一按 UTC 字面换算——LLM 心算毫秒会偏移窗口，
     * 日期字面量是首选形态）。
     */
    public record RangeDto(Object min, Object max) {
    }
}

package com.iris.lite.java.api.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.lite.java.context.schema.EntitySchema;
import com.iris.lite.java.context.schema.SchemaManager;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 动态 MCP 工具注册器。
 *
 * <p><b>双模式</b>（{@code iris.mcp.dynamic-tools.mode}）：
 * <ul>
 *   <li><b>all</b>（全量注册）：为每个实体生成工具并全部注册进 MCP Server，
 *       tools/list 返回全量目录——实体多时目录体积与工具数成正比，会撑爆
 *       consuming Agent 的上下文；</li>
 *   <li><b>on-demand</b>（默认，按需发现）：动态工具<b>不注册</b>进 tools/list，
 *       改维护内存索引（解除每实体截断上限），只注册两个固定发现工具——
 *       {@code search_entity_tools}（关键词搜工具）与 {@code call_entity_tool}
 *       （按名称分发执行，执行路径与 all 模式完全相同）。tools/list 恒定 25 个（23 静态 + search/call 两件套）。</li>
 * </ul>
 *
 * <p><b>对账模型</b>：周期 diff「当前 Schema 应生成的工具集」与「已注册/已索引集」。
 * 规格指纹（name+description+inputSchema 的 hash）变化即触发更新——Schema 热载
 * 改字段描述/索引声明也能生效。
 *
 * <p><b>防工具爆炸</b>：all 模式下 {@code iris.mcp.dynamic-tools.max-per-entity}
 * （默认 8）限制单实体工具数；on-demand 模式索引不进 LLM 上下文，不截断。
 */
@Component
public class DynamicEntityToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(DynamicEntityToolRegistrar.class);

    /** 运行模式。 */
    private enum Mode {
        /** 全量注册进 tools/list。 */
        ALL,
        /** 按需发现：内存索引 + search/call 两个固定工具。 */
        ON_DEMAND
    }

    /** 变更日志里最多列出的工具名数（防首轮/大变更刷屏）。 */
    private static final int LOG_NAME_LIMIT = 40;

    private final ObjectProvider<McpSyncServer> mcpServerProvider;
    private final EntityToolFactory factory;
    private final DynamicToolExecutor executor;
    private final EntityToolIndex toolIndex;
    private final SchemaManager schemaManager;
    private final ObjectMapper objectMapper;

    private final boolean enabled;
    private final Mode mode;
    private final int maxPerEntity;

    /** all 模式：已注册的动态工具 toolName -> 规格指纹。 */
    private final Map<String, String> registered = new ConcurrentHashMap<>();

    /** on-demand 模式：两个发现工具只在首轮注册一次（规格不变，无需指纹对账）。 */
    private final AtomicBoolean discoveryRegistered = new AtomicBoolean(false);

    public DynamicEntityToolRegistrar(
            ObjectProvider<McpSyncServer> mcpServerProvider,
            EntityToolFactory factory,
            DynamicToolExecutor executor,
            EntityToolIndex toolIndex,
            SchemaManager schemaManager,
            ObjectMapper objectMapper,
            @Value("${iris.mcp.dynamic-tools.enabled:true}") boolean enabled,
            @Value("${iris.mcp.dynamic-tools.mode:on-demand}") String mode,
            @Value("${iris.mcp.dynamic-tools.max-per-entity:8}") int maxPerEntity) {
        this.mcpServerProvider = mcpServerProvider;
        this.factory = factory;
        this.executor = executor;
        this.toolIndex = toolIndex;
        this.schemaManager = schemaManager;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.maxPerEntity = maxPerEntity;
        Mode parsed;
        try {
            // 连字符归一为下划线：on-demand -> ON_DEMAND
            parsed = Mode.valueOf(mode.trim().replace('-', '_').toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            log.warn("iris.mcp.dynamic-tools.mode 非法值 '{}'，回落 on-demand", mode);
            parsed = Mode.ON_DEMAND;
        }
        this.mode = parsed;
    }

    /**
     * 应用就绪后做首次注册。
     *
     * <p><b>为什么监听 ApplicationReadyEvent 而不是 @PostConstruct</b>：
     * MCP Server 的 bean 要等 Web 容器初始化完才可用，
     * 构造期拿不到，必须等就绪事件。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            log.info("动态 MCP 工具未启用（iris.mcp.dynamic-tools.enabled=false）");
            return;
        }
        if (mcpServerProvider.getIfAvailable() == null) {
            log.warn("McpSyncServer 不可用，动态 MCP 工具不注册");
            return;
        }
        log.info("动态 MCP 工具模式: {}", mode == Mode.ALL ? "all（全量注册）" : "on-demand（按需发现）");
        reconcile();
    }

    /**
     * 周期对账：与当前 Schema 集合 diff，增删/更新动态工具（或索引）并通知客户端。
     *
     * <p>无变更时静默返回——每 3 秒一次的空对账不该产生日志。
     */
    @Scheduled(fixedDelayString = "${iris.mcp.dynamic-tools.poll-interval-ms:3000}")
    public void reconcile() {
        if (!enabled || mcpServerProvider.getIfAvailable() == null) {
            return;
        }
        McpSyncServer server = mcpServerProvider.getObject();
        try {
            if (mode == Mode.ALL) {
                reconcileAll(server);
            } else {
                reconcileOnDemand(server);
            }
        } catch (Exception e) {
            log.warn("动态 MCP 工具对账失败: {}", e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------------------------------
    // all 模式：全量注册
    // -------------------------------------------------------------------------------------------------

    private void reconcileAll(McpSyncServer server) {
        // 期望状态：当前生效 Schema 应生成的全部工具（含规格指纹）
        Map<String, EntityToolFactory.ToolDef> desired = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>();
        for (EntitySchema schema : schemaManager.list()) {
            for (EntityToolFactory.ToolDef def : factory.buildToolDefs(schema, taken, maxPerEntity)) {
                desired.put(def.name(), def);
            }
        }
        List<String> changed = new ArrayList<>();
        // 新增或规格变化：指纹不等 = 描述/参数变了，remove+add 换新
        for (EntityToolFactory.ToolDef def : desired.values()) {
            String fp = registered.get(def.name());
            if (fp == null) {
                server.addTool(executor.toSpec(def));
                registered.put(def.name(), factory.fingerprint(def));
                changed.add("+ " + def.name());
            } else if (!fp.equals(factory.fingerprint(def))) {
                server.removeTool(def.name());
                server.addTool(executor.toSpec(def));
                registered.put(def.name(), factory.fingerprint(def));
                changed.add("~ " + def.name());
            }
        }
        // 消失：已注册里有、Schema 里没了（YAML 被删掉/字段去掉索引声明）
        for (String toolName : List.copyOf(registered.keySet())) {
            if (!desired.containsKey(toolName)) {
                server.removeTool(toolName);
                registered.remove(toolName);
                changed.add("- " + toolName);
            }
        }
        if (!changed.isEmpty()) {
            server.notifyToolsListChanged();
            log.info("动态 MCP 工具已更新（{}），当前 {} 个: {}", changed, registered.size(),
                    String.join(", ", registered.keySet()));
        }
    }

    // -------------------------------------------------------------------------------------------------
    // on-demand 模式：内存索引 + 两个发现工具
    // -------------------------------------------------------------------------------------------------

    private void reconcileOnDemand(McpSyncServer server) {
        ensureDiscoveryToolsRegistered(server);
        // 期望索引：当前生效 Schema 应生成的全部工具（不截断——索引不进 LLM 上下文）
        Map<String, EntityToolFactory.ToolDef> desired = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>();
        for (EntitySchema schema : schemaManager.list()) {
            for (EntityToolFactory.ToolDef def : factory.buildToolDefs(schema, taken, Integer.MAX_VALUE)) {
                desired.put(def.name(), def);
            }
        }
        Map<String, ToolIndexSnapshot.IndexedTool> current = toolIndex.current().byName();
        if (unchanged(current, desired)) {
            return;
        }
        // 计算变更明细（+ 新增 / ~ 规格变化 / - 下线）
        List<String> changed = new ArrayList<>();
        for (String name : desired.keySet()) {
            ToolIndexSnapshot.IndexedTool cur = current.get(name);
            if (cur == null) {
                changed.add("+ " + name);
            } else if (!cur.fingerprint().equals(factory.fingerprint(desired.get(name)))) {
                changed.add("~ " + name);
            }
        }
        for (String name : current.keySet()) {
            if (!desired.containsKey(name)) {
                changed.add("- " + name);
            }
        }
        toolIndex.replace(desired);
        server.notifyToolsListChanged();
        if (current.isEmpty()) {
            // 首轮初始化：只报总数，不列全量名字（千级名字刷屏无意义）
            log.info("动态 MCP 工具已更新（on-demand 索引初始化 {} 个工具）", desired.size());
        } else {
            log.info("动态 MCP 工具已更新（on-demand 索引重建 {} 个工具，变更 {} 个）: {}",
                    desired.size(), changed.size(),
                    changed.size() > LOG_NAME_LIMIT
                            ? String.join(", ", changed.subList(0, LOG_NAME_LIMIT)) + " …"
                            : String.join(", ", changed));
        }
    }

    /**
     * 快照是否与期望定义集一致（无变化则跳过重建）。
     * 快路径：Schema 实例引用相同 ⇒ 派生的描述/参数必然相同；
     * 慢路径（热载后新实例）：回退到指纹比对。
     */
    private boolean unchanged(Map<String, ToolIndexSnapshot.IndexedTool> current,
                              Map<String, EntityToolFactory.ToolDef> desired) {
        if (current.size() != desired.size()) {
            return false;
        }
        for (Map.Entry<String, EntityToolFactory.ToolDef> e : desired.entrySet()) {
            ToolIndexSnapshot.IndexedTool cur = current.get(e.getKey());
            if (cur == null) {
                return false;
            }
            EntityToolFactory.ToolDef def = e.getValue();
            if (cur.def().schema() != def.schema()) {
                // Schema 实例换了（热载）：只有内容真的变了才需要重建
                if (!cur.fingerprint().equals(factory.fingerprint(def))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 幂等注册两个发现工具（规格恒定，无需指纹对账）。 */
    private void ensureDiscoveryToolsRegistered(McpSyncServer server) {
        if (!discoveryRegistered.compareAndSet(false, true)) {
            return;
        }
        server.addTool(searchToolSpec());
        server.addTool(callToolSpec());
        log.info("按需发现工具已注册: search_entity_tools, call_entity_tool");
    }

    /** search_entity_tools：关键词搜索工具索引；空 query 返回目录概览。 */
    private McpServerFeatures.SyncToolSpecification searchToolSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "关键词，匹配工具名/实体名/namespace/字段名/字段中文描述（可用实体名、字段名或其业务含义关键词）；留空返回目录概览"));
        properties.put("limit", Map.of(
                "type", "integer",
                "description", "最多返回条数，默认 8，上限 20"));
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("search_entity_tools")
                .description("按需发现动态数据工具：本服务的数据查询工具不直接出现在 tools/list，"
                        + "先用本工具按关键词搜索候选工具（支持实体名/字段名/中文语义），"
                        + "再用 call_entity_tool 执行选中的工具。")
                .inputSchema(McpSchema.JsonSchema.builder().type("object").properties(properties).build())
                .build();
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            try {
                Map<String, Object> args = request.arguments();
                String query = args.get("query") instanceof String s ? s : "";
                int limit = args.get("limit") instanceof Number n ? n.intValue()
                        : ToolIndexSnapshot.DEFAULT_LIMIT;
                ToolIndexSnapshot snapshot = toolIndex.current();
                ToolIndexSnapshot.SearchResult result = snapshot.search(query, limit);
                Map<String, Object> out = new LinkedHashMap<>();
                if (result.blank()) {
                    out.put("mode", "overview");
                    out.put("totalTools", snapshot.byName().size());
                    out.put("namespaces", namespaceStats(snapshot));
                    out.put("usage", "传 query 搜索具体工具（支持实体名/字段名/中文关键词），"
                            + "再用 call_entity_tool(name, arguments) 执行");
                } else {
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (ToolIndexSnapshot.IndexedTool t : result.items()) {
                        items.add(toSearchItem(t));
                    }
                    out.put("query", query);
                    out.put("total", result.totalMatches());
                    out.put("returned", items.size());
                    out.put("tools", items);
                    out.put("usage", result.items().isEmpty()
                            ? "未找到匹配工具，试试更短的关键词（如字段英文名或实体全名的片段），或用空 query 查看目录概览"
                            : "用 call_entity_tool(name, arguments) 执行选中工具；arguments 结构见各条目的 parameters");
                }
                return McpSchema.CallToolResult.builder()
                        .addTextContent(objectMapper.writeValueAsString(out))
                        .build();
            } catch (Exception e) {
                log.debug("search_entity_tools 失败: {}", e.getMessage(), e);
                return McpSchema.CallToolResult.builder()
                        .addTextContent("工具搜索失败: " + e.getMessage())
                        .isError(true)
                        .build();
            }
        });
    }

    /** call_entity_tool：按名称从索引分发执行，执行路径与 all 模式直调完全一致。 */
    private McpServerFeatures.SyncToolSpecification callToolSpec() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of(
                "type", "string",
                "description", "目标动态工具名，来自 search_entity_tools 的结果"));
        properties.put("arguments", Map.of(
                "type", "object",
                "description", "目标工具的参数，结构见 search 结果条目中的 parameters"));
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("call_entity_tool")
                .description("执行 search_entity_tools 返回的动态数据工具（查询实体数据）。"
                        + "请先 search_entity_tools 再调用本工具；"
                        + "固定工具（query_entity / get_related_entities / 记忆 / 缓存等）不经过本工具，直接调用。")
                .inputSchema(McpSchema.JsonSchema.builder().type("object").properties(properties).build())
                .build();
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            try {
                Map<String, Object> args = request.arguments();
                String name = args.get("name") instanceof String s ? s : null;
                if (name == null || name.isBlank()) {
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("缺少 name 参数：请传入 search_entity_tools 结果中的工具名")
                            .isError(true)
                            .build();
                }
                ToolIndexSnapshot.IndexedTool target = toolIndex.current().byName().get(name);
                if (target == null) {
                    return McpSchema.CallToolResult.builder()
                            .addTextContent("未知工具 '" + name
                                    + "'。请先调 search_entity_tools 获取有效工具名"
                                    + "（固定工具如 query_entity / get_related_entities 请直接调用）")
                            .isError(true)
                            .build();
                }
                Map<String, Object> toolArgs = args.get("arguments") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m : Map.of();
                // 与 all 模式直调共用同一执行路径（身份/授权/参数转换/错误语义零差异）
                return executor.execute(target.def(), toolArgs);
            } catch (Exception e) {
                log.debug("call_entity_tool 分发失败: {}", e.getMessage(), e);
                return McpSchema.CallToolResult.builder()
                        .addTextContent("工具调用失败: " + e.getMessage())
                        .isError(true)
                        .build();
            }
        });
    }

    /** 单个搜索结果条目：定位信息 + 描述 + 完整参数定义（Agent 看完即可直接调用）。 */
    private Map<String, Object> toSearchItem(ToolIndexSnapshot.IndexedTool t) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", t.def().name());
        item.put("namespace", t.def().schema().namespace());
        item.put("entity", t.def().schema().entity());
        item.put("kind", t.def().kind());
        item.put("description", t.description());
        item.put("parameters", t.parameters());
        return item;
    }

    /** 目录概览：按 namespace 统计实体数与工具数。 */
    private List<Map<String, Object>> namespaceStats(ToolIndexSnapshot snapshot) {
        Map<String, Set<String>> entitiesByNs = new LinkedHashMap<>();
        Map<String, Integer> toolsByNs = new LinkedHashMap<>();
        for (ToolIndexSnapshot.IndexedTool t : snapshot.byName().values()) {
            String ns = t.def().schema().namespace();
            entitiesByNs.computeIfAbsent(ns, k -> new HashSet<>()).add(t.def().schema().entity());
            toolsByNs.merge(ns, 1, Integer::sum);
        }
        List<Map<String, Object>> stats = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : entitiesByNs.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("namespace", e.getKey());
            row.put("entities", e.getValue().size());
            row.put("tools", toolsByNs.get(e.getKey()));
            stats.add(row);
        }
        return stats;
    }
}

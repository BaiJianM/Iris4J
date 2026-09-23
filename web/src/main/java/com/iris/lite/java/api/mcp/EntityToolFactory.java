package com.iris.lite.java.api.mcp;

import com.iris.lite.java.context.schema.EntitySchema;
import com.iris.lite.java.context.schema.FieldSchema;
import com.iris.lite.java.context.schema.FieldType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 动态实体工具的<b>定义生成器</b>（从 {@link DynamicEntityToolRegistrar} 抽取，无状态）。
 *
 * <p><b>工具生成规则</b>：
 * 按字段索引形态生成语义化工具，工具名即意图，Agent 不必自己组装过滤对象：
 * <ul>
 *   <li>主键（单主键）→ {@code get_{entity}_by_{pk}}；</li>
 *   <li>tag 字段 → {@code filter_{entity}_by_{field}}；</li>
 *   <li>numeric 字段 → {@code find_{entity}_by_{field}_range}；</li>
 *   <li>text 字段 → 单字段用 {@code search_{entity}_by_text}，
 *       多字段加后缀 {@code search_{entity}_by_{field}}。</li>
 * </ul>
 * 每实体同时保留通用工具 {@code query_{entity}}（字段裁剪/组合过滤/分页）。
 *
 * <p><b>all / on-demand 双模式共用</b>：all 模式（直接注册进 tools/list）传真实
 * {@code maxPerEntity} 截断；on-demand 模式（工具进内存索引按需发现）传
 * {@link Integer#MAX_VALUE} 不截断——索引不进 LLM 上下文，无截断必要。
 */
@Component
public class EntityToolFactory {

    private static final Logger log = LoggerFactory.getLogger(EntityToolFactory.class);

    private static final String TOOL_NAME_PREFIX = "query_";

    /** 语义化工具的生成/截断优先级（get 已按生成顺序在最前）。 */
    private static final List<String> FIELD_PRIORITY = List.of("get", "range", "filter", "search");

    /**
     * 单个动态工具的定义：名称 + 所属 Schema + 类型（query/get/filter/range/search）+ 目标字段。
     */
    public record ToolDef(String name, EntitySchema schema, String kind, FieldSchema field) {
    }

    /**
     * 为单个 Schema 生成工具定义列表（通用 + 语义化）。
     *
     * <p>名称冲突规则：工具名在 MCP 协议里全局唯一，同名会覆盖。
     * 跨 namespace 出现同名实体/同名字段时，后来者自动加 ns 前缀消歧
     * （{@code query_{ns}_{entity}} 形态，与既有行为一致）。
     *
     * <p>截断规则：总数超 {@code maxPerEntity} 时按 主键 > numeric > tag > text
     * 的优先级保留（最常用的先留）；传 {@link Integer#MAX_VALUE} 表示不截断
     * （on-demand 索引模式）。
     */
    public List<ToolDef> buildToolDefs(EntitySchema schema, Set<String> taken, int maxPerEntity) {
        List<ToolDef> defs = new ArrayList<>();
        // 1) 通用工具（永远保留，不受截断影响语义——它是兜底查询入口）
        defs.add(new ToolDef(uniqueName(taken, schema, TOOL_NAME_PREFIX + schema.entity()),
                schema, "query", null));
        // 2) 语义化工具按优先级排序生成，超限截断
        List<ToolDef> semantic = new ArrayList<>();
        // 主键工具：仅单主键生成（复合主键无法用单值参数表达）
        if (schema.primaryKeys().size() == 1) {
            String pk = schema.primaryKeys().get(0);
            schema.fields().stream().filter(f -> f.name().equals(pk)).findFirst()
                    .ifPresent(f -> semantic.add(new ToolDef(
                            uniqueName(taken, schema, "get_" + schema.entity() + "_by_" + pk),
                            schema, "get", f)));
        }
        // numeric 范围 / tag 等值 / text 检索，按声明顺序
        for (FieldSchema f : schema.fields()) {
            if (f.name().equals(schema.primaryKeys().size() == 1 ? schema.primaryKeys().get(0) : "")) {
                continue;
            }
            String idx = f.effectiveIndex();
            if ("numeric".equals(idx)) {
                semantic.add(new ToolDef(uniqueName(taken, schema,
                        "find_" + schema.entity() + "_by_" + f.name() + "_range"), schema, "range", f));
            }
        }
        for (FieldSchema f : schema.fields()) {
            if ("tag".equals(f.effectiveIndex()) && !schema.primaryKeys().contains(f.name())) {
                semantic.add(new ToolDef(uniqueName(taken, schema,
                        "filter_" + schema.entity() + "_by_" + f.name()), schema, "filter", f));
            }
        }
        List<FieldSchema> textFields = schema.fields().stream()
                .filter(f -> "text".equals(f.effectiveIndex())).toList();
        for (FieldSchema f : textFields) {
            // 单 text 字段用 search_{entity}_by_text；多字段加字段名后缀
            String name = textFields.size() == 1
                    ? "search_" + schema.entity() + "_by_text"
                    : "search_" + schema.entity() + "_by_" + f.name();
            semantic.add(new ToolDef(uniqueName(taken, schema, name), schema, "search", f));
        }
        // 按优先级截断（get 已在最前，超出部分丢弃并告警）。
        // 用独立变量收结果：semantic 不能重赋值（上面 lambda 已捕获，须保持 effectively final）。
        // on-demand 索引模式传 MAX_VALUE 不截断，也不产生告警（告警只对 tools/list 上下文有意义）
        List<ToolDef> kept = semantic;
        if (maxPerEntity != Integer.MAX_VALUE) {
            int quota = Math.max(1, maxPerEntity) - 1; // 减去通用工具占的 1 个名额
            if (semantic.size() > quota) {
                log.warn("实体 {}/{} 语义化工具数 {} 超过上限 {}，按 主键>numeric>tag>text 截断",
                        schema.namespace(), schema.entity(), semantic.size(), quota);
                kept = semantic.subList(0, Math.max(0, quota));
            }
        }
        defs.addAll(kept);
        defs.forEach(d -> taken.add(d.name()));
        return defs;
    }

    /**
     * 工具名唯一化：基础名空闲就用基础名；冲突（跨 ns 同名实体/字段）加 ns 前缀。
     */
    public String uniqueName(Set<String> taken, EntitySchema schema, String base) {
        if (!taken.contains(base)) {
            return base;
        }
        String prefixed = schema.namespace() + "_" + base;
        // 前缀名也冲突时（理论罕见）：追加数字后缀兜底，保证对账不丢工具
        String candidate = prefixed;
        int i = 2;
        while (taken.contains(candidate)) {
            candidate = prefixed + "_" + i++;
        }
        return candidate;
    }

    /**
     * 生成工具描述：通用工具带字段清单；语义化工具"名即意图" +
     * 字段 description（Schema 提供的业务语义直接喂给 LLM）。
     */
    public String buildDescription(ToolDef def) {
        EntitySchema schema = def.schema();
        FieldSchema field = def.field();
        StringBuilder sb = new StringBuilder();
        String entityDesc = schema.entity();
        switch (def.kind()) {
            case "query" -> {
                String fields = schema.fields().stream()
                        .map(f -> f.name() + "(" + f.type() + ")"
                                + (f.description() == null ? "" : " " + f.description()))
                        .collect(Collectors.joining(", "));
                sb.append("查询实体 ").append(entityDesc)
                        .append("（namespace ").append(schema.namespace()).append("，entity 已固化无需传）。")
                        .append("可用字段: ").append(fields).append("。");
                // 元数据增强：明确结果形态、裁剪行为与能力边界，
                // 引导模型精准取数而不是整页拉取
                sb.append("结果含 total（匹配总数）与分页信息；结果超过 50 行会被截断，")
                        .append("请尽量用 filters 收窄条件、fields 只取需要的列。");
                // MCP 面未提供聚合工具：明确「不可解」出口，防止模型穷举分页自行累计
                sb.append("本工具无聚合能力——统计/求和/排名类问题无法完成，请直接说明并给出替代标准，")
                        .append("严禁逐页拉取全量数据自行累计。");
                // 把外键关系写进工具描述——Agent 不必调 get_schema 就知道实体怎么连，
                // 指纹含描述，此处变化即触发对账 remove+add + tools/list_changed
                List<FieldSchema> fks = schema.foreignKeyFields();
                if (!fks.isEmpty()) {
                    sb.append("实体关系: ").append(fks.stream()
                                    .map(fk -> fk.name() + "→" + fk.relatedEntity())
                                    .collect(Collectors.joining(", ")))
                            .append("（跨实体导航用 get_related_entities）。");
                }
            }
            case "get" -> sb.append("按主键精确获取单个 ").append(entityDesc)
                    .append("。").append(fieldDesc(field, "主键"));
            case "filter" -> sb.append("按 ").append(def.field().name())
                    .append(" 精确过滤查询 ").append(entityDesc).append(" 列表。")
                    .append(fieldDesc(field, null));
            case "range" -> sb.append("按 ").append(def.field().name())
                    .append(" 的数值范围（闭区间，min/max 至少传一个）查询 ")
                    .append(entityDesc).append(" 列表。").append(fieldDesc(field, null));
            case "search" -> sb.append("对 ").append(def.field().name())
                    .append(" 做全文检索（多词 AND）查询 ").append(entityDesc).append(" 列表。")
                    .append(fieldDesc(field, null));
            default -> sb.append("查询实体 ").append(entityDesc).append("。");
        }
        if (schema.tenantField() != null) {
            sb.append("多租户实体，tenant 必填。");
        }
        return sb.toString();
    }

    /**
     * 字段说明片段：Schema 有 description/values 用之，否则给类型兜底说明。
     *
     * <p><b>values 只进语义化工具（get/filter/range/search），不进 query 工具的
     * 全字段清单</b>——后者已列全部字段，再拼值域会把工具描述撑爆
     * （工具描述常驻 MCP 工具目录，长度有预算）；语义化工具一个工具只盯一个字段，
     * 值域（如 {@code 0=未支付; 1=已支付}）正是模型构造过滤条件时最需要的语境。
     * 注意：description/values 参与 fingerprint，此处变化触发 MCP 工具对账
     * remove+add 与 tools/list_changed。
     */
    public String fieldDesc(FieldSchema field, String fallback) {
        if (field == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (field.description() != null) {
            sb.append("字段语义: ").append(field.description());
        }
        if (field.values() != null) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append("取值: ").append(field.values());
        }
        if (sb.length() > 0) {
            return sb.toString();
        }
        return fallback == null ? "" : "（" + fallback + "）";
    }

    /** 生成输入参数定义（fingerprint 与 inputSchema 构建共用，保证两者所见一致）。 */
    public Map<String, Object> inputProperties(ToolDef def) {
        EntitySchema schema = def.schema();
        Map<String, Object> properties = new LinkedHashMap<>();
        switch (def.kind()) {
            case "query" -> {
                String filterable = schema.fields().stream()
                        .map(FieldSchema::name)
                        .collect(Collectors.joining(", "));
                properties.put("fields", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "description", "要返回的字段列表，为空返回全部字段"));
                properties.put("filters", Map.of(
                        "type", "object",
                        "description", "等值过滤（字段名→值），可用字段: " + filterable));
                properties.put("rangeFilters", Map.of(
                        "type", "object",
                        "description", "范围过滤（字段名→{min,max}，端点可缺省），"
                                + "仅 numeric 索引字段有效。日期/时间字段必须传日期字符串"
                                + "（如 \"2026-04-01\"），服务端按 UTC 字面换算——"
                                + "严禁心算 epoch 毫秒；非时间数值字段传数字"));
                properties.put("textFilters", Map.of(
                        "type", "object",
                        "description", "全文匹配（字段名→文本，多词 AND），"
                                + "仅 text 索引字段有效，可用字段: "
                                + schema.fields().stream()
                                        .filter(f -> "text".equals(f.effectiveIndex()))
                                        .map(FieldSchema::name)
                                        .collect(Collectors.joining(", "))));
                properties.put("page", Map.of(
                        "type", "integer",
                        "description", "页码，从 1 开始，默认 1"));
                properties.put("pageSize", Map.of(
                        "type", "integer",
                        "description", "每页大小，默认 20，上限 200"));
                properties.put("sortField", Map.of(
                        "type", "string",
                        "description", "排序字段（索引字段），如 "
                                + schema.fields().stream()
                                        .filter(f -> f.index() != null)
                                        .map(FieldSchema::name)
                                        .limit(5)
                                        .collect(Collectors.joining("/"))
                                + " 等；缺省按主键升序"));
                properties.put("sortDesc", Map.of(
                        "type", "boolean",
                        "description", "是否降序，配合 sortField 使用"));
            }
            case "get", "filter" -> properties.put(def.field().name(), Map.of(
                    "type", jsonTypeOf(def.field().type()),
                    "description", paramDesc(def.field(), def.schema())));
            case "range" -> {
                // 端点同时接受数字与日期字面量（RangeFilter.parse 统一换算）——
                // 类型不按字段名猜测，描述一句话覆盖两种形态
                properties.put("min", Map.of(
                        "type", "number",
                        "description", "下界（含），可省略。数值字段传数字；"
                                + "日期字段传日期字符串（如 \"2026-04-01\"，"
                                + "服务端按 UTC 字面换算），严禁心算毫秒"));
                properties.put("max", Map.of(
                        "type", "number",
                        "description", "上界（含），可省略。数值字段传数字；"
                                + "日期字段传日期字符串（如 \"2026-06-30\" 覆盖该日全天）"));
            }
            case "search" -> properties.put("query", Map.of(
                    "type", "string",
                    "description", paramDesc(def.field(), def.schema()) + "，多词 AND"));
            default -> { }
        }
        // 语义化工具：多租户实体才带 tenant 参；非多租户实体不带（tenant 参数无意义）。
        // 通用 query 工具永远带（探索新实体时需要知道租户要求），说明按实体情况区分
        if (schema.tenantField() != null && !"query".equals(def.kind())) {
            properties.put("tenant", Map.of(
                    "type", "string",
                    "description", "租户 ID，多租户实体必填"));
        } else if ("query".equals(def.kind())) {
            properties.put("tenant", Map.of(
                    "type", "string",
                    "description", schema.tenantField() != null
                            ? "租户 ID，多租户实体必填"
                            : "租户 ID，可省略"));
        }
        return properties;
    }

    /** 参数说明：字段 description 优先，缺省退化为"字段名 + 类型"。 */
    public String paramDesc(FieldSchema field, EntitySchema schema) {
        if (field.description() != null) {
            return field.description();
        }
        if (schema.primaryKeys().contains(field.name())) {
            return "主键值";
        }
        return field.name() + " 的取值";
    }

    /** FieldType -> JSON Schema 类型映射（数值/时间统一 number，时间以毫秒数传参）。 */
    public String jsonTypeOf(FieldType type) {
        return switch (type) {
            case INT, LONG, DOUBLE, TIMESTAMP -> "number";
            default -> "string";
        };
    }

    /**
     * 规格指纹：description + 输入参数定义的 hash。
     * Schema 热载改了字段描述/索引声明 → 指纹变化 → 对账触发更新
     * 与 tools/list_changed（all 模式）或索引重建（on-demand 模式）。
     */
    public String fingerprint(ToolDef def) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String seed = buildDescription(def) + "|"
                    + inputProperties(def).toString();
            return HexFormat.of().formatHex(md.digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("规格指纹计算失败", e);
        }
    }
}

package com.iris.lite.application.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具目录：给 LLM 的 function calling 目录声明。
 *
 * <p><b>与 MCP 工具面的关系</b>：这里精选 MCP 静态工具 + 动态查询能力的
 * 统一入口形态——动态注册器按实体展开成 N 个 query_{entity} 工具（MCP 目录
 * 便于客户端按实体浏览），Agent 目录用<b>一个带 entity 参数的 query_entity</b>
 * （12 个工具已接近模型可靠选择的边界，按实体展开会把目录撑到数十项）。
 * 执行路径完全一致：都落到同一批 application 服务（见 {@link AgentToolDispatcher}）。
 *
 * <p><b>裁剪原则</b>：只读 + 记忆写入两类。DLQ 重放、BGSAVE、Schema 编辑、
 * agent key 管理等写路径/管理面能力不进 Agent 目录——演示场景不需要，
 * 也避免 Agent 在无人确认时触发有副作用的运维动作。
 */
public final class AgentToolCatalog {

    private AgentToolCatalog() {
    }

    /** JSON Schema 属性名（目录构造大量复用，字面量收口为常量防拼写漂移）。 */
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_ENTITY = "entity";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_ARRAY = "array";

    /** 全量目录（operator / 鉴权关闭场景）。 */
    public static List<AgentChatClient.ToolSpec> all() {
        List<AgentChatClient.ToolSpec> tools = new ArrayList<>();
        tools.add(queryEntity());
        tools.add(aggregateEntity());
        tools.add(related());
        tools.add(getSchema());
        tools.add(askClarification());
        tools.add(searchLongTerm());
        tools.add(saveLongTerm());
        tools.add(getWorking());
        tools.add(saveWorking());
        tools.add(searchWorking());
        tools.add(llmCacheStats());
        tools.add(redisStatus());
        tools.add(cdcSources());
        tools.add(dlqList());
        return List.copyOf(tools);
    }

    /** 查询/记忆子集（受限 agent 身份：无运维观测工具）。 */
    public static List<AgentChatClient.ToolSpec> restricted() {
        return List.of(queryEntity(), aggregateEntity(), related(), getSchema(), askClarification(),
                searchLongTerm(), saveLongTerm(), getWorking(), saveWorking(), searchWorking());
    }

    /**
     * 澄清工具（交互式问答澄清）：模型对歧义问题主动发起澄清。
     *
     * <p>它不是数据工具——{@code AgentToolDispatcher} 不感知它，由
     * {@code DefaultAgentService} 在分发循环里<b>拦截</b>：向端侧发 {@code clarify}
     * 事件（前端渲染选项卡片 + 「其他」输入框）后当前轮干净终止，用户答复作为
     * 下一条消息携「澄清答复：」前缀重新入队续跑。
     */
    public static AgentChatClient.ToolSpec askClarification() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("question", str("澄清问题本身，一句话说清要确认什么标准/范围"));
        props.put("options", Map.of(
                "type", FIELD_ARRAY, "items", Map.of("type", "string"),
                FIELD_DESCRIPTION, "2-4 个互斥的可选标准，必须来自真实存在的字段/时间窗/分组维度，禁止开放式空问"));
        return new AgentChatClient.ToolSpec("ask_clarification",
                "向用户发起澄清提问（呈现为可点选的选项卡片，另含自由填写项）。"
                        + "仅当问题存在歧义或关键标准缺失（时间范围、统计标准、分组维度、实体指代等）时调用；"
                        + "调用后本轮结束等待答复，用户答复会自动带回继续。不要用它闲聊或确认已明确的内容。",
                objectSchema(props, List.of("question", "options")));
    }

    private static AgentChatClient.ToolSpec queryEntity() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(FIELD_ENTITY, str("实体名（须为 Schema 摘要中已注册的实体）"));
        props.put("filters", Map.of(
                "type", "object",
                FIELD_DESCRIPTION, "等值过滤条件，字段名->值（值可为单值或数组=任一命中，"
                        + "数组长度上限 200，超出报错）；不传=查全部"));
        props.put("range_filters", Map.of(
                "type", "object",
                FIELD_DESCRIPTION, "范围过滤，字段名->{min,max}（端点可为 null=开放）。"
                        + "日期/时间字段【必须】传日期字符串（如 \"2026-04-01\"、\"2026-04-01 00:00:00\"），"
                        + "服务端按 UTC 字面换算——严禁自己心算 epoch 毫秒（必错）；"
                        + "非时间数值字段传数字"));
        props.put("text_filters", Map.of(
                "type", "object", FIELD_DESCRIPTION, "全文匹配，字段名->文本（字段须有 text 索引）"));
        props.put("tenant", str("租户标识；多租户实体必填（tenantField 声明的字段）"));
        props.put("fields", Map.of("type", FIELD_ARRAY, "items", Map.of("type", "string"),
                FIELD_DESCRIPTION, "只返回这些字段；不传=全部。尽量只取需要的列，可显著减小结果体积"));
        props.put("page", Map.of("type", "integer", FIELD_DESCRIPTION, "页码，从 1 开始"));
        props.put("page_size", Map.of("type", "integer", FIELD_DESCRIPTION, "每页条数，默认 20"));
        props.put("sort_field", str("排序字段（须为索引字段，如数值/TAG 字段）；不传=按主键升序"));
        props.put("sort_desc", Map.of("type", "boolean",
                FIELD_DESCRIPTION, "是否降序，配合 sort_field 使用（如金额最高的前 N 条）"));
        return new AgentChatClient.ToolSpec("query_entity",
                "按索引查询实体数据（等值/范围/全文过滤 + 任意索引字段排序，自动走租户隔离与访问控制）。"
                        + "结果含 total（匹配总数）与分页信息；结果超过 50 行会被截断，请用 filters/fields 精准取数。"
                        + "本工具无聚合能力——统计/求和/平均/排名类问题必须改用 aggregate_entity。"
                        + "跨实体关联请先用本工具拿主键，再用 get_related_entities 导航。",
                objectSchema(props, List.of(FIELD_ENTITY)));
    }

    /**
     * 聚合工具：统计/TopN/分布类问题的直接解。
     * FT.AGGREGATE 服务端完成分组归约，不占模型 token；仅支持已索引字段（fail-closed）。
     */
    public static AgentChatClient.ToolSpec aggregateEntity() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(FIELD_ENTITY, str("实体名（须为 Schema 摘要中已注册的实体）"));
        props.put("group_by", Map.of("type", FIELD_ARRAY, "items", Map.of("type", "string"),
                FIELD_DESCRIPTION, "分组字段（须已索引）；不传=全表单组（只算总量）。"
                        + "支持维度路径 fk字段->维表实体.维度字段："
                        + "按维表上的维度归并统计（须与该字段的 relatedEntity 声明一致），"
                        + "返回行含 inputGroups（归并前组数）与 totalGroups（归并后组数）"));
        Map<String, Object> metricItem = new LinkedHashMap<>();
        metricItem.put("type", "object");
        metricItem.put("properties", Map.of(
                "op", Map.of("type", "string",
                        FIELD_DESCRIPTION, "聚合操作：count/sum/avg/min/max"),
                "field", Map.of("type", "string",
                        FIELD_DESCRIPTION, "聚合字段（sum/avg/min/max 必填且须为 numeric 索引；count 无需）")));
        metricItem.put(FIELD_DESCRIPTION, "归约指标清单；不传=默认 count 计数");
        props.put("metrics", Map.of(
                "type", FIELD_ARRAY,
                "items", metricItem,
                FIELD_DESCRIPTION, "归约指标清单；不传=默认 count 计数"));
        props.put("filters", Map.of(
                "type", "object",
                FIELD_DESCRIPTION, "等值过滤，字段名->值（值可为单值或数组=任一命中，"
                        + "数组长度上限 200，超出报错——大批量筛选请缩小范围或分批查询）"));
        props.put("range_filters", Map.of(
                "type", "object",
                FIELD_DESCRIPTION, "范围过滤，字段名->{min,max}。"
                        + "日期/时间字段【必须】传日期字符串（如 \"2026-04-01\"、\"2026-07-01\"），"
                        + "服务端按 UTC 字面换算——严禁自己心算 epoch 毫秒（必错）；"
                        + "非时间数值字段传数字。max 传日期-only 时覆盖该日全天"));
        props.put("tenant", str("租户标识；多租户实体必填"));
        props.put("sort_by", str("排序别名：分组字段名或指标别名。指标别名不可自定义，"
                + "固定为 count 或 <op>_<字段名> 形态（如 count、sum_amount、avg_price）——"
                + "metrics 里不要传 alias 参数（不支持，会被忽略）"));
        props.put("sort_desc", Map.of("type", "boolean",
                FIELD_DESCRIPTION, "是否降序（TopN 场景传 true）"));
        props.put("limit", Map.of("type", "integer",
                FIELD_DESCRIPTION, "返回组数上限（TopN 的 N），默认 20，上限 100"));
        return new AgentChatClient.ToolSpec("aggregate_entity",
                "服务端聚合：按字段分组统计（count/sum/avg/min/max）+ TopN 排序，"
                        + "一次调用出结果，服务端完成计算不占上下文。"
                        + "凡「每个X有多少/求和/平均/最大/最小/排名前N/按天分布」类问题必须优先用本工具；"
                        + "仅支持已索引字段（未索引字段会明确报错）。结果含 totalGroups（分组总数）与 rows。",
                objectSchema(props, List.of(FIELD_ENTITY)));
    }

    private static AgentChatClient.ToolSpec related() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(FIELD_ENTITY, str("源实体名"));
        props.put("id", str("源实体主键值"));
        props.put("field", str("限定外键字段名；不传=返回全部正向+反向关系"));
        props.put("page", Map.of("type", "integer"));
        props.put("page_size", Map.of("type", "integer"));
        return new AgentChatClient.ToolSpec("get_related_entities",
                "跨实体关系导航：给定某行主键，取它的外键指向行（正向）或引用它的行（反向）。"
                        + "路径形态：源实体.fk字段 -> 目标实体（以 Schema 摘要中的关联标注为准）。",
                objectSchema(props, List.of(FIELD_ENTITY, "id")));
    }

    private static AgentChatClient.ToolSpec getSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(FIELD_ENTITY, str("实体名；不传=列出全部实体及其字段/关系概要"));
        return new AgentChatClient.ToolSpec("get_schema",
                "查询实体 Schema：字段名/类型/索引/主键/外键关系（relatedEntity），"
                        + "构造查询参数前先用它确认字段名与租户要求。",
                objectSchema(props, List.of()));
    }

    private static AgentChatClient.ToolSpec searchLongTerm() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", str("检索文本"));
        props.put("limit", Map.of("type", "integer", FIELD_DESCRIPTION, "返回条数，默认 5"));
        props.put("mode", str("检索模式 keyword/semantic/hybrid，默认 hybrid"));
        props.put("owner", str("按归属过滤（ownerId）；不传=全部"));
        return new AgentChatClient.ToolSpec("search_long_term_memory",
                "检索长期记忆（跨会话持久），三模式：关键词/语义向量/混合。",
                objectSchema(props, List.of("query")));
    }

    private static AgentChatClient.ToolSpec saveLongTerm() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(FIELD_CONTENT, str("记忆内容（一句话一个事实，便于去重）"));
        props.put("type", str("记忆类型，如 fact/preference；默认 fact"));
        props.put("owner", str("归属（ownerId），默认 default"));
        return new AgentChatClient.ToolSpec("save_long_term_memory",
                "写入一条长期记忆（带向量，语义去重），供跨会话检索。",
                objectSchema(props, List.of(FIELD_CONTENT)));
    }

    private static AgentChatClient.ToolSpec getWorking() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("session_id", str("会话 id；不传=当前会话"));
        return new AgentChatClient.ToolSpec("get_working_memory",
                "读取当前会话的工作记忆（滚动摘要 + 全部条目）。",
                objectSchema(props, List.of()));
    }

    private static AgentChatClient.ToolSpec saveWorking() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(FIELD_CONTENT, str("要记住的会话内信息"));
        props.put("session_id", str("会话 id；不传=当前会话"));
        return new AgentChatClient.ToolSpec("save_working_memory",
                "向当前会话的工作记忆追加一条信息（达到阈值会自动触发 LLM 抽取晋升长期记忆）。",
                objectSchema(props, List.of(FIELD_CONTENT)));
    }

    private static AgentChatClient.ToolSpec searchWorking() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("query", str("检索文本"));
        props.put("limit", Map.of("type", "integer"));
        props.put("session_id", str("会话 id；不传=当前会话"));
        return new AgentChatClient.ToolSpec("search_working_memory",
                "在当前会话工作记忆中做语义检索。",
                objectSchema(props, List.of("query")));
    }

    private static AgentChatClient.ToolSpec llmCacheStats() {
        return new AgentChatClient.ToolSpec("llm_cache_stats",
                "LLM 语义缓存统计：条目数/命中分布/守卫拦截数（fenceMiss）等。",
                objectSchema(Map.of(), List.of()));
    }

    private static AgentChatClient.ToolSpec redisStatus() {
        return new AgentChatClient.ToolSpec("redis_status",
                "Redis 运维快照：dbsize/内存/AOF/RDB 状态。",
                objectSchema(Map.of(), List.of()));
    }

    private static AgentChatClient.ToolSpec cdcSources() {
        return new AgentChatClient.ToolSpec("cdc_sources",
                "CDC 数据集成管道视图：每个 source 的流/消费组/积压(lag)/PEL/死信数。",
                objectSchema(Map.of(), List.of()));
    }

    private static AgentChatClient.ToolSpec dlqList() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("limit", Map.of("type", "integer", FIELD_DESCRIPTION, "最大条数，默认 20"));
        return new AgentChatClient.ToolSpec("dlq_list",
                "查看 CDC 死信队列（DLQ）条目：投影失败的事件与失败原因。",
                objectSchema(props, List.of()));
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", FIELD_DESCRIPTION, description);
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }
}

package com.iris.lite.context.schema;

import com.iris.lite.shared.error.ErrorCode;
import com.iris.lite.shared.error.IrisException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实体 Schema 定义。描述一个实体有哪些字段、主键是什么、是否多租户。
 *
 * <p><b>来源</b>：classpath 下的 {@code iris/schema/**}{@code /*.yml}（基线，不可变）
 * 与外部目录 {@code iris.schema.dir} 下的 {@code *.yml}（可热加载覆盖）。
 * 由 {@link SchemaProvider} 解析加载。
 *
 * <p><b>Schema 是查询链路的第一道闸门</b>：字段裁剪要校验字段名合法性、
 * 租户隔离要知道用哪个字段做行级过滤、语义缓存要靠字段类型判断哪些值可以模糊匹配。
 * 没有 Schema，这些都做不了。
 *
 * <p><b>accessTagField（access tags 行级可见性）</b>：可选。声明后，该字段的值
 * 被解释为"本行数据的可见性 tag"——Agent 必须持有该 tag 才能看到此行。
 * 与 tenantField 的区别：tenant 是"属于哪个数据域"（必填硬隔离），
 * accessTagField 是"这个 Agent 被授权看哪些行"（一个 Agent 可持有多个 tag，
 * 查询时翻译为 {@code @field:{tag1|tag2}} 多值下推）。
 *
 * @param namespace      命名空间
 * @param entity         实体名
 * @param primaryKeys    主键字段名列表（当前为单主键，取第 0 个）
 * @param fields         字段定义列表
 * @param tenantField    多租户字段名；null 表示该实体不做行级租户隔离
 * @param accessTagField 行级可见性 tag 字段名；null 表示不做 tag 级行过滤
 * @param description    表级业务用途描述（一句话，可空）；Agent 一级 Schema 摘要
 *                       用它选表，null 时摘要退化为只列表名
 */
public record EntitySchema(
        String namespace,
        String entity,
        List<String> primaryKeys,
        List<FieldSchema> fields,
        String tenantField,
        String accessTagField,
        String description) {

    /**
     * 兼容构造器：4 参形态（无行级 tag 字段）。
     * YAML 解析与测试代码里的旧调用点无需同步修改。
     */
    public EntitySchema(String namespace, String entity, List<String> primaryKeys,
                        List<FieldSchema> fields, String tenantField) {
        this(namespace, entity, primaryKeys, fields, tenantField, null, null);
    }

    /**
     * 兼容构造器：6 参形态（无 description），
     * 既有调用点（仓储外 Schema 重建等）无需同步修改。
     */
    public EntitySchema(String namespace, String entity, List<String> primaryKeys,
                        List<FieldSchema> fields, String tenantField, String accessTagField) {
        this(namespace, entity, primaryKeys, fields, tenantField, accessTagField, null);
    }

    /**
     * 紧凑构造器：主键必填，其余归一化。
     *
     * <p>主键为空的 Schema 毫无意义——投影定位、缓存失效、DLQ 重放全都要靠主键，
     * 因此这里直接抛 {@link IllegalArgumentException} 让启动即失败，
     * 而不是等运行期出现 NPE。
     */
    public EntitySchema {
        if (primaryKeys == null || primaryKeys.isEmpty()) {
            throw new IllegalArgumentException("primaryKeys 不能为空");
        }
        if (fields == null) {
            fields = List.of();
        }
        // 空字符串租户字段视同"非多租户实体"，避免下游判空与判空串分叉
        if (tenantField != null && tenantField.isBlank()) {
            tenantField = null;
        }
        if (accessTagField != null && accessTagField.isBlank()) {
            accessTagField = null;
        }
        // 空白表级描述视同未声明，避免摘要里出现空括号
        if (description != null && description.isBlank()) {
            description = null;
        }
        validateIndexedFields(primaryKeys, fields, tenantField, accessTagField);
    }

    /**
     * 校验索引声明：
     * <ul>
     *   <li>主键字段必须 indexed——FT.SEARCH 的 {@code SORTBY id ASC} 要靠索引字段排序，
     *       没进索引就没法保证深翻页稳定；</li>
     *   <li>多租户实体的 tenantField 必须 indexed——租户过滤是每个查询都要下推的条件，
     *       不进索引等于每次查询都要在索引命中后再回表校验，隔离语义也不完整；</li>
     *   <li>accessTagField 必须 indexed 且是已声明字段——行级 tag 过滤同样是
     *       每次查询都要下推的条件（{@code @field:{tag1|tag2}}）。</li>
     * </ul>
     *
     * <p>校验失败抛 {@link IllegalArgumentException}：Schema 是启动期/热加载期一次性解析的
     * 低频配置，宁可 fail-fast 也不带病运行（运行期才发现等于查询悄悄退化为 SCAN，
     * 更糟的是行过滤可能根本没生效——那是越权）。
     */
    private static void validateIndexedFields(
            List<String> primaryKeys, List<FieldSchema> fields,
            String tenantField, String accessTagField) {
        // 参与索引的判定统一走 effectiveIndex()：显式 index 声明与旧 indexed: true
        // 等价（声明 tag/numeric/text 即视为参与索引），两套写法都能通过校验
        Map<String, String> indexType = new HashMap<>();
        if (fields != null) {
            for (FieldSchema f : fields) {
                indexType.put(f.name(), f.effectiveIndex());
            }
        }
        if (primaryKeys != null) {
            for (String pk : primaryKeys) {
                if (indexType.get(pk) == null) {
                    throw new IllegalArgumentException(
                            "主键字段必须声明 indexed: true 或 index: tag/numeric"
                                    + "（SORTBY 稳定分页依赖索引）: " + pk);
                }
            }
        }
        if (tenantField != null && indexType.get(tenantField) == null) {
            throw new IllegalArgumentException(
                    "多租户字段必须声明 indexed: true 或 index: tag"
                            + "（租户过滤需下推到索引）: " + tenantField);
        }
        if (accessTagField != null && indexType.get(accessTagField) == null) {
            throw new IllegalArgumentException(
                    "accessTagField 必须声明 indexed: true 或 index: tag"
                            + "（行级 tag 过滤需下推到索引）: " + accessTagField);
        }
        // 外键字段必须参与索引——关系查询翻译为"目标实体 WHERE fk = 值"，
        // 没索引就会退化成 SCAN（违反查询红线），宁可加载期 fail-fast
        if (fields != null) {
            for (FieldSchema f : fields) {
                if (f.isForeignKey() && indexType.get(f.name()) == null) {
                    throw new IllegalArgumentException(
                            "外键字段（relatedEntity=" + f.relatedEntity() + "）必须声明"
                                    + " indexed: true 或 index: tag/numeric"
                                    + "（关系查询需下推到索引）: " + f.name());
                }
                // 携带字段的 FK 路径部分也必须参与索引——主表变更回刷子表时
                // 按 fk 值反查子表行，没索引就是全库 SCAN
                if (f.isCarried()) {
                    String fkField = f.fkFieldOfCarriedFrom();
                    if (fkField == null || !indexType.containsKey(fkField)) {
                        throw new IllegalArgumentException(
                                "携带字段（carriedFrom=" + f.carriedFrom() + "）的"
                                        + "fk 路径部分必须指向本实体已声明字段: " + f.name());
                    }
                    if (indexType.get(fkField) == null) {
                        throw new IllegalArgumentException(
                                "携带字段（carriedFrom=" + f.carriedFrom() + "）的 fk 字段"
                                        + "必须声明 indexed: true 或 index: tag/numeric"
                                        + "（回刷按 fk 反查子表行需下推到索引）: " + fkField);
                    }
                    if (!indexType.containsKey(f.name())) {
                        throw new IllegalArgumentException(
                                "携带字段本身必须声明 indexed: true 或 index: tag/numeric"
                                        + "（携带的目的就是可过滤/可聚合）: " + f.name());
                    }
                }
            }
        }
    }

    /**
     * 本实体声明的全部外键字段（relatedEntity 非空），保持声明顺序。
     * 空列表 = 本实体不指向任何实体（但仍可能有别的实体指向它——反向关系）。
     */
    public List<FieldSchema> foreignKeyFields() {
        return fields.stream().filter(FieldSchema::isForeignKey).toList();
    }

    /** 本实体声明的全部携带字段（carriedFrom 非空），保持声明顺序；空列表 = 无。 */
    public List<FieldSchema> carriedFields() {
        return fields.stream().filter(FieldSchema::isCarried).toList();
    }

    /** 按名找外键字段；不是外键或不存在返回 null。 */
    public FieldSchema foreignKeyField(String name) {
        for (FieldSchema f : fields) {
            if (f.name().equals(name) && f.isForeignKey()) {
                return f;
            }
        }
        return null;
    }

    /**
     * 判断持有给定 tag 集合的 Agent 是否可见某字段（字段级裁剪用）。
     *
     * @param fieldName 字段名；未声明字段返回 false（按"不存在"处理）
     * @param agentTags Agent 的 tag 集合
     */
    public boolean isFieldVisible(String fieldName, Set<String> agentTags) {
        for (FieldSchema f : fields) {
            if (f.name().equals(fieldName)) {
                return f.visibleTo(agentTags);
            }
        }
        return false;
    }

    /**
     * 对 Agent 可见的全部字段名（未声明 tags 的字段恒在列）。
     */
    public List<String> visibleFields(Set<String> agentTags) {
        return fields.stream()
                .filter(f -> f.visibleTo(agentTags))
                .map(FieldSchema::name)
                .toList();
    }

    /**
     * 校验请求的 fields 是否全部存在于 Schema；存在非法字段名即抛异常。
     *
     * <p>提前校验的意义：不校验的话非法字段会被静默忽略（裁剪结果里少几个字段），
     * 调用方拿到残缺数据却不知道字段名写错了。显式报错 IRIS-1002 更利于 Agent 自我纠正。
     *
     * <p><b>可见性校验的边界选择</b>：请求了无权字段<b>不报错而是静默剔除</b>——
     * Agent 的 fields 列表常来自工具描述里的完整字段清单，让它逐字段预判权限
     * 违背"平台裁剪、调用方无感"的设计；报错反而泄露"该字段存在"这一信息。
     *
     * @param requested 请求返回的字段列表；null 或空表示返回全部字段，直接通过
     * @throws IrisException 含未声明字段时抛 FIELD_NOT_FOUND
     */
    public void validateFields(List<String> requested) {
        // 空 = 返回全部字段，无需校验
        if (requested == null || requested.isEmpty()) {
            return;
        }
        var valid = fields.stream().map(FieldSchema::name).toList();
        for (String f : requested) {
            if (!valid.contains(f)) {
                throw new IrisException(ErrorCode.FIELD_NOT_FOUND,
                        "字段不存在: " + f);
            }
        }
    }
}

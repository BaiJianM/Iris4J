package com.iris4j.context.schema;

import java.util.List;
import java.util.Set;

/**
 * 单个字段的 Schema 定义。
 *
 * <p><b>type 的用途</b>：语义缓存靠它区分"可以模糊匹配的值"和"必须精确匹配的值"。
 * 只有 {@link FieldType#STRING} 的过滤值参与向量相似度比较；
 * 数字/布尔/时间类型（如 {@code level=1} 与 {@code level=2}）字面极像但语义完全不同，
 * 绝不能模糊命中，必须走精确等值。
 *
 * <p><b>indexed 与 index 的用途</b>：声明该字段是否参与 Query Engine 二级索引。
 * 两者的关系（向后兼容设计）：
 * <ul>
 *   <li>旧方式 {@code indexed: true}：不指定索引形态，按 type 推导
 *       （STRING/BOOLEAN→TAG，数值/时间→NUMERIC）；</li>
 *   <li>新方式 {@code index: tag|numeric|text}：显式声明索引形态，
 *       声明即视为参与索引（无需再写 indexed: true）；TEXT 形态用于
 *       search_{entity}_by_text 类全文检索工具。</li>
 * </ul>
 * 为什么由 Schema 显式声明而不是全字段建索引：标定数据——全字段索引（含 TEXT）的
 * 内存开销与 JSON 文档本身相当（10 万条约 22.7 MB），只建过滤字段能省七八成。
 * 主键与租户字段由 {@link EntitySchema} 构造器强制要求 indexed。
 *
 * <p><b>tags 的用途（access tags）</b>：声明字段的可见性门槛——持有
 * <b>任一</b>列出的 tag 的 Agent 才能在结果中看到该字段；未声明 tags 的字段
 * 对所有 Agent 公开。这是 "per-agent access tags 控制
 * 可见数据"的字段级落地：敏感字段（如 phone/id_card）标 tags 后，
 * 未授权 Agent 的查询结果里自动剔除，无需每个调用方自觉遵守约定。
 *
 * <p><b>description 的用途</b>：给 LLM 看的字段语义说明，写进动态生成的
 * MCP 工具描述与参数说明，Agent 不必靠字段名猜含义。
 *
 * <p><b>values 的用途（渐进式披露第二跳的值域语义）</b>：声明枚举/状态类字段的
 * 取值含义（如 {@code 0=未支付; 1=已支付; 2=支付后取消}），由 get_schema 输出。
 * 只有字段名和 description 时，模型知道字段"是什么"，不知道"取什么值、各值什么意思"
 * ——聚合标准（营收只算已支付）类问题的错答根源就在这一层。自由文本而非 Map：
 * 值域不总是纯枚举（如 "1-5 级，5 最高"），文本形态对 LLM 最直接。
 *
 * <p><b>carriedFrom 的用途（跨表携带）</b>：声明本字段是<b>投影期从主表
 * 携带</b>的冗余字段，格式 {@code fk字段->主表实体.主表字段}
 * （如 {@code order_id->ord_order.pay_status}）。CDC 投影子表行时按声明从主表
 * 投影行回填，主表该字段变更时回刷子表。解决什么问题：单表聚合引擎没有 JOIN，
 * 「明细按主表状态过滤」类标准（销量只算已支付）跨不过表去——把过滤字段
 * 携带进子表，查询侧保持单表一跳。携带什么由接入方在 Schema 里声明
 * （业务标准不进 core），机制只认声明。
 *
 * <p><b>relatedEntity 的用途（跨实体关系）</b>：
 * 把字段声明为<b>外键</b>，指向同 namespace 下的另一个实体（值为目标实体的主键）。
 * 声明后 {@code get_related_entities} 即可跨实体导航：
 * <ul>
 *   <li><b>正向</b>：order.customer_id -> customer，给定订单查它的客户（多对一）；</li>
 *   <li><b>反向</b>：给定客户查所有指向它的订单（一对多）——无需在 customer 侧
 *       重复声明，关系图从全部 Schema 的 FK 声明推导。</li>
 * </ul>
 * FK 字段必须参与索引（EntitySchema 构造器强制校验）：关系查询翻译为
 * {@code 目标实体 WHERE fk = 值}，没有索引就会退化成 SCAN，违反查询红线。
 *
 * @param name          字段名（与源表列名一致）
 * @param type          字段类型
 * @param indexed       是否参与索引（旧声明方式；index 显式声明后可省略）
 * @param tags          字段可见性 tag 清单；空/无 = 公开字段；agent 持有任一 tag 即可见
 * @param index         显式索引形态："tag" | "numeric" | "text"；空 = 未显式声明，
 *                      回落 indexed 布尔推导。YAML 层面解析非法值会被忽略并告警
 * @param description   字段语义说明（喂给 LLM），可空
 * @param relatedEntity 外键指向的目标实体名（须同 namespace 存在）；null = 普通字段
 * @param values        取值含义说明（枚举/状态类字段，喂给 LLM），可空；
 *                      如 {@code "0=未支付; 1=已支付; 2=支付后取消"}
 * @param carriedFrom   跨表携带来源，可空。格式
 *                      {@code fk字段->主表实体.主表字段}；声明后本字段为投影期
 *                      从主表回填/回刷的冗余字段，本实体即可单表过滤主表标准
 */
public record FieldSchema(
        String name,
        FieldType type,
        boolean indexed,
        List<String> tags,
        String index,
        String description,
        String relatedEntity,
        String values,
        String carriedFrom) {

    /**
     * 便利构造器：不声明 indexed 时默认 false（代码内构造路径，YAML 解析必须走全参）。
     */
    public FieldSchema(String name, FieldType type) {
        this(name, type, false, List.of(), null, null, null, null, null);
    }

    /**
     * 便利构造器：声明 indexed 但无 tags（既有调用形态，保持兼容）。
     */
    public FieldSchema(String name, FieldType type, boolean indexed) {
        this(name, type, indexed, List.of(), null, null, null, null, null);
    }

    /**
     * 便利构造器：4 参形态（无 index/description），保持兼容。
     */
    public FieldSchema(String name, FieldType type, boolean indexed, List<String> tags) {
        this(name, type, indexed, tags, null, null, null, null, null);
    }

    /** 便利构造器：6 参形态（无 relatedEntity），保持兼容。 */
    public FieldSchema(String name, FieldType type, boolean indexed, List<String> tags,
                       String index, String description) {
        this(name, type, indexed, tags, index, description, null, null, null);
    }

    /** 便利构造器：7 参形态（无 values），保持兼容。 */
    public FieldSchema(String name, FieldType type, boolean indexed, List<String> tags,
                       String index, String description, String relatedEntity) {
        this(name, type, indexed, tags, index, description, relatedEntity, null, null);
    }

    /** 便利构造器：值域引入后的 8 参形态（无 carriedFrom），保持兼容。 */
    public FieldSchema(String name, FieldType type, boolean indexed, List<String> tags,
                       String index, String description, String relatedEntity, String values) {
        this(name, type, indexed, tags, index, description, relatedEntity, values, null);
    }

    /** 紧凑构造器：tags 归一化为不可变空列表；字符串字段归一、空串转 null。 */
    public FieldSchema {
        tags = tags == null ? List.of() : List.copyOf(tags);
        index = index == null || index.isBlank() ? null : index.trim().toLowerCase();
        description = description == null || description.isBlank() ? null : description.trim();
        relatedEntity = relatedEntity == null || relatedEntity.isBlank()
                ? null : relatedEntity.trim();
        values = values == null || values.isBlank() ? null : values.trim();
        carriedFrom = carriedFrom == null || carriedFrom.isBlank()
                ? null : carriedFrom.trim();
    }

    /** 是否为携带字段（声明了 carriedFrom）。 */
    public boolean isCarried() {
        return carriedFrom != null;
    }

    /**
     * 解析 carriedFrom 的 fk 字段部分：{@code order_id->ord_order.pay_status}
     * → {@code order_id}。格式非法（无 {@code ->}）返回 null——加载期由
     * {@link EntitySchema} 校验 fail-fast，这里只做无状态解析。
     */
    public String fkFieldOfCarriedFrom() {
        if (carriedFrom == null) {
            return null;
        }
        int i = carriedFrom.indexOf("->");
        return i <= 0 ? null : carriedFrom.substring(0, i).trim();
    }

    /**
     * 解析 carriedFrom 的目标实体部分：{@code order_id->ord_order.pay_status}
     * → {@code ord_order}。格式非法返回 null。
     */
    public String parentEntityOfCarriedFrom() {
        if (carriedFrom == null) {
            return null;
        }
        int i = carriedFrom.indexOf("->");
        if (i <= 0) {
            return null;
        }
        String rest = carriedFrom.substring(i + 2).trim();
        int dot = rest.indexOf('.');
        return dot <= 0 ? null : rest.substring(0, dot).trim();
    }

    /**
     * 解析 carriedFrom 的主表字段部分：{@code order_id->ord_order.pay_status}
     * → {@code pay_status}。格式非法返回 null。
     */
    public String parentFieldOfCarriedFrom() {
        if (carriedFrom == null) {
            return null;
        }
        int i = carriedFrom.indexOf("->");
        if (i <= 0) {
            return null;
        }
        String rest = carriedFrom.substring(i + 2).trim();
        int dot = rest.indexOf('.');
        return dot < 0 || dot == rest.length() - 1 ? null : rest.substring(dot + 1).trim();
    }

    /** 是否为外键字段（声明了 relatedEntity）。 */
    public boolean isForeignKey() {
        return relatedEntity != null;
    }

    /**
     * 解析字段的<b>实际生效索引形态</b>，translator 与索引管理器共用这一个方法推导，
     * 避免两处各推一套导致"建索引的形态"和"翻译查询的形态"分叉。
     *
     * <p>推导规则（优先级从高到低）：
     * <ol>
     *   <li>显式 {@code index} 声明 → 直接生效；其中 text 仅对 STRING 合法，
     *       其余类型声明 text 降级为 tag（声明期会告警，这里兜底保证行为确定）；</li>
     *   <li>无显式声明但 {@code indexed: true} → 旧推导：STRING/BOOLEAN→tag、
     *       数值/时间→numeric，与历史行为逐字节一致；</li>
     *   <li>两者皆无 → {@code null}，不参与索引。</li>
     * </ol>
     *
     * @return "tag" | "numeric" | "text"，null 表示不进索引
     */
    public String effectiveIndex() {
        if (index != null) {
            // 兜底：text 只对 STRING 有意义（分词匹配），其余类型按 tag 处理
            if ("text".equals(index) && type != FieldType.STRING) {
                return "tag";
            }
            return index;
        }
        if (indexed) {
            return (type == FieldType.STRING || type == FieldType.BOOLEAN) ? "tag" : "numeric";
        }
        return null;
    }

    /**
     * 判断持有给定 tag 集合的 Agent 是否可见本字段。
     *
     * <p>语义：<b>任一命中即可见</b>（agent.tags ∩ field.tags ≠ ∅）；
     * 字段未声明 tags 时对所有人可见。
     *
     * <p><b>通配约定</b>：tags 含 {@code "*"} 的身份（legacy 旧单 key）不设限，
     * 所有字段可见。{"@code *"} 仅供内部兼容身份使用——per-agent 配置里
     * 千万不要给真实 Agent 配 {@code *}，等于关闭访问治理。
     */
    public boolean visibleTo(Set<String> agentTags) {
        if (tags.isEmpty() || agentTags.contains("*")) {
            return true;
        }
        for (String t : tags) {
            if (agentTags.contains(t)) {
                return true;
            }
        }
        return false;
    }
}

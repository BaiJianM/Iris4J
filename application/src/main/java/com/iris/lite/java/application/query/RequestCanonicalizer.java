package com.iris.lite.java.application.query;

import com.iris.lite.java.context.model.QueryRequest;
import com.iris.lite.java.context.schema.EntitySchema;
import com.iris.lite.java.context.schema.FieldSchema;
import com.iris.lite.java.context.schema.FieldType;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * 查询请求的规范化器——两级缓存 key 成分清单的<b>唯一权威出处</b>。
 *
 * <p><b>解决什么问题</b>：精确缓存（{@link CachedEntityQueryService}）与语义缓存
 * （{@link SemanticCachedEntityQueryService}）共用同一份「哪些请求成分
 * 参与 key、如何消除顺序差异」清单。未来给 QueryRequest 增加新的过滤形态时
 * 若两处标准不一致，就会出现「查询 A 条件命中 B 条件缓存」的数据串号——这类漂移
 * 编译期完全不可见。本类把清单集中到唯一一处，两种模式共用同一组件顺序与
 * 同一条 STRING 分类规则，只差 STRING 值的处理方式。
 *
 * <p><b>两种模式</b>：
 * <ul>
 *   <li><b>exact</b>（schema 传 null）：全部过滤值进 key——精确缓存必须全量区分，
 *       不同条件的结果天差地别；</li>
 *   <li><b>semantic</b>（schema 非 null）：仅 Schema 声明为 STRING 的过滤<b>标量值</b>
 *       被剔除出 key（只保留键存在性，键集合必须精确一致候选才同构）——这些值进
 *       语义文本参与向量相似；其余（非 STRING 值/集合值/范围/文本/排序）字面太像，
 *       模糊命中会返回错误数据集，必须精确匹配（安全红线，见
 *       {@link #isSemanticValue}）。</li>
 * </ul>
 *
 * <p><b>格式约定</b>：{@code |} 分隔组件、{@code =} 连接键值、键带类型前缀
 * （f.=等值条件 / sk.=语义值键存在性 / r.=范围 / t.=文本）防跨组件歧义；
 * TreeMap 消除 filters/range/text 的顺序差异、fields 排序消除字段顺序差异。
 * 产物只作哈希输入，从不解析。过滤键是 Schema 字段名（字母数字下划线），
 * 不存在含 {@code |=} 的键造成歧义的现实路径。
 */
final class RequestCanonicalizer {

    private RequestCanonicalizer() {
    }

    /**
     * 规范化一个查询请求为稳定字符串（同语义请求恒得同一串）。
     *
     * @param schema STRING 分类依据（semantic 模式）；null = exact 模式（全量进 key）
     */
    static String canonical(QueryRequest request, EntitySchema schema) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("ns=").append(request.namespace())
                .append("|entity=").append(request.entity())
                .append("|tenant=").append(request.tenant() == null ? "" : request.tenant())
                .append("|fields=").append(request.fields().stream().sorted().toList())
                .append("|page=").append(request.page())
                .append("|size=").append(request.pageSize())
                .append("|sort=").append(request.sortField() == null ? "" : request.sortField())
                .append("|desc=").append(request.sortDesc());
        // filters 用 TreeMap 消除调用方传参顺序差异（同条件不同顺序必须命中同一 key）
        for (Map.Entry<String, ?> e : new TreeMap<>(request.filters()).entrySet()) {
            if (schema != null && isSemanticValue(schema, e.getKey(), e.getValue())) {
                // semantic 模式：STRING 标量值剔除，只保留键存在性
                sb.append("|sk.").append(e.getKey());
            } else {
                sb.append("|f.").append(e.getKey()).append('=').append(e.getValue());
            }
        }
        new TreeMap<>(request.rangeFilters())
                .forEach((k, v) -> sb.append("|r.").append(k).append('=').append(v));
        new TreeMap<>(request.textFilters())
                .forEach((k, v) -> sb.append("|t.").append(k).append('=').append(v));
        return sb.toString();
    }

    /**
     * 过滤值是否为「可语义模糊匹配」的 STRING 标量值——STRING 分类的唯一判定出处。
     *
     * <p>三类值必须走精确匹配（进 key，不进语义文本）：
     * <ul>
     *   <li><b>集合值</b>（平台注入的 access tags 授权集合）：若参与向量相似，
     *       「tag 集合字面相似的两个 Agent」会跨权限命中对方的语义条目——行级越权通道；</li>
     *   <li><b>非 STRING 类型</b>：数字/布尔/时间字面太像（{@code level=1} 与
     *       {@code level=2}），模糊命中会返回错误数据集；</li>
     *   <li><b>未声明字段</b>（Schema 查不到类型，如动态字段）：保守地走精确匹配。</li>
     * </ul>
     */
    static boolean isSemanticValue(EntitySchema schema, String field, Object value) {
        if (value instanceof Collection<?>) {
            return false;
        }
        return schema.fields().stream()
                .filter(f -> f.name().equals(field))
                .findFirst()
                .map(FieldSchema::type)
                .orElse(null) == FieldType.STRING;
    }
}

package com.iris.lite.infrastructure.redis;

import com.iris.lite.context.model.RangeFilter;
import com.iris.lite.context.schema.EntitySchema;
import com.iris.lite.context.schema.FieldSchema;
import com.iris.lite.context.schema.FieldType;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 过滤条件 → FT.SEARCH 查询串的纯函数翻译器。
 *
 * <p><b>为什么做成纯函数</b>：翻译规则（TAG 转义、数值解析、字段下推判断）
 * 是最容易出错也最容易测错的部分，脱离 Redis 单跑、规则集中一处，
 * 出问题时不用起服务就能复现。
 *
 * <p><b>翻译规则</b>：
 * <ul>
 *   <li>TAG 字段（STRING/BOOLEAN）：{@code @city:{"上海"}}——值整体包双引号，
 *       内部的 {@code \} 与 {@code "} 反斜杠转义。永远加引号是防查询串注入：
 *       过滤值来自外部 Agent，裸拼时值里的空格/@/冒号都会改变查询语义。</li>
 *   <li>NUMERIC 字段（INT/LONG/DOUBLE/TIMESTAMP）：等值 {@code @level:[3 3]}；
 *       范围 {@code @price:[10 100]}，缺端点用 {@code -inf}/{@code +inf}。</li>
 *   <li>TEXT 字段（显式 index: text 的 STRING）：{@code @name:(tok1 tok2)}——
 *       分词后逐 token 引号包裹，token 之间隐式 AND。</li>
 *   <li>多个条件空格连接，FT.SEARCH 默认交集（AND）语义。</li>
 *   <li>无过滤条件 → {@code *}（全量，仍受 LIMIT 分页约束）。</li>
 * </ul>
 *
 * <p><b>下推不了的怎么办</b>：字段没声明索引、NUMERIC 值解析不成数字、
 * TEXT 字段收到集合值等，返回 null，由调用方（{@code LettuceEntityProjectionRepository}）
 * 降级回 SCAN 内存过滤路径。降级保证语义不回退——宁可慢一点，不能少返回或错返回。
 */
public final class SearchQueryTranslator {

    /** 工具类禁止实例化。 */
    private SearchQueryTranslator() {
    }

    /** Query Engine 索引字段类型（与 RedisAdapter.FtField.type 的合法取值对应）。 */
    public enum FtType {
        /** TAG：精确等值匹配，适合枚举/字符串/布尔。 */
        TAG,
        /** NUMERIC：等值与范围匹配，适合数字与时间。 */
        NUMERIC,
        /** TEXT：分词全文匹配，适合需要检索的长文本（仅显式 index: text 声明）。 */
        TEXT
    }

    /** 由 Schema 字段类型推导索引类型（旧 indexed: true 布尔路径）。 */
    public static FtType ftTypeOf(FieldType type) {
        // 穷举 FieldType 全部取值（switch 表达式编译期强制完整；TEXT 类型不建索引无映射）
        return switch (type) {
            case STRING, BOOLEAN -> FtType.TAG;
            case INT, LONG, DOUBLE, TIMESTAMP -> FtType.NUMERIC;
        };
    }

    /**
     * 解析字段的<b>实际生效</b>索引类型；不参与索引返回 null。
     *
     * <p>统一走 {@link FieldSchema#effectiveIndex()} 推导——显式 {@code index:}
     * 声明优先，缺省回落旧 indexed 布尔推导。<b>translator 与
     * {@code EntityIndexManager} 必须共用本方法</b>，保证"建了什么索引"
     * 和"敢往哪个索引下推"是同一套判断；两处各推一套迟早分叉
     * （比如 TEXT 字段翻译成 TAG 查询 → 语义错误或永远查不到）。
     */
    public static FtType indexTypeOf(FieldSchema field) {
        String effective = field.effectiveIndex();
        if (effective == null) {
            return null;
        }
        return switch (effective) {
            case "tag" -> FtType.TAG;
            case "numeric" -> FtType.NUMERIC;
            case "text" -> FtType.TEXT;
            // effectiveIndex() 只可能产出这三种值，防御性返回 null 走降级
            default -> null;
        };
    }

    /**
     * 把等值过滤条件翻译为 FT.SEARCH 查询串（旧签名，无范围/文本条件）。
     *
     * @param filters 等值过滤（字段名 -> 单值或集合值），可空
     * @param schema  实体 Schema，提供字段类型与索引声明
     * @return 查询串；存在无法下推的条件时返回 null（调用方降级 SCAN）
     */
    public static String translate(Map<String, Object> filters, EntitySchema schema) {
        return translate(filters, null, null, schema);
    }

    /**
     * 把等值/范围/文本过滤条件翻译为 FT.SEARCH 查询串。
     *
     * <p>字段名即索引别名（建索引时 {@code AS <字段名>}），无需额外映射。
     *
     * <p><b>集合值</b>：TAG 字段值为 {@code Collection} 时翻译为
     * {@code @f:{"v1"|"v2"}}（任一命中）——access tags 行级过滤的形态
     * （Agent 可持有多个可见性 tag）；NUMERIC/TEXT 字段收到集合值返回 null 降级。
     * 单值路径行为不受影响。
     *
     * @param filters      等值过滤（字段名 -> 单值或集合值），可空
     * @param rangeFilters 数值范围过滤（字段名 -> 范围），可空
     * @param textFilters  TEXT 全文匹配（字段名 -> 文本），可空
     * @param schema       实体 Schema，提供字段类型与索引声明
     * @return 查询串；存在无法下推的条件时返回 null（调用方降级 SCAN）
     */
    public static String translate(Map<String, Object> filters,
                                   Map<String, RangeFilter> rangeFilters,
                                   Map<String, String> textFilters,
                                   EntitySchema schema) {
        boolean noFilters = filters == null || filters.isEmpty();
        boolean noRanges = rangeFilters == null || rangeFilters.isEmpty();
        boolean noTexts = textFilters == null || textFilters.isEmpty();
        if (noFilters && noRanges && noTexts) {
            return "*";
        }
        Map<String, FieldSchema> byName = new LinkedHashMap<>();
        for (FieldSchema f : schema.fields()) {
            byName.put(f.name(), f);
        }
        StringBuilder sb = new StringBuilder();
        // ---------- 等值过滤 ----------
        if (!noFilters) {
            for (Map.Entry<String, Object> e : filters.entrySet()) {
                FieldSchema field = byName.get(e.getKey());
                // Schema 没声明的字段不该走到这里（应用层已校验），防御性降级
                FtType indexType = field == null ? null : indexTypeOf(field);
                if (indexType == null) {
                    return null;
                }
                // 集合值：TAG 多值 OR（任一命中即匹配）；其余形态集合无语义 → 降级
                if (e.getValue() instanceof Collection<?> values) {
                    if (indexType != FtType.TAG || values.isEmpty()) {
                        return null;
                    }
                    appendTagOr(sb, field.name(), values);
                    continue;
                }
                String value = String.valueOf(e.getValue());
                switch (indexType) {
                    // TEXT 字段收到等值条件：走单 token 匹配（等值下推为分词匹配，
                    // 语义上是"包含该词"而非"全等"——TEXT 字段本就没有全等语义）
                    case TAG -> sb.append('@').append(field.name()).append(":{")
                            .append(quoteTag(value)).append("} ");
                    case NUMERIC -> {
                        // 数值解析失败（如 level=abc）→ 本字段无法下推，整体降级
                        String numeric = toNumericRangeValue(value);
                        if (numeric == null) {
                            return null;
                        }
                        sb.append('@').append(field.name()).append(":[")
                                .append(numeric).append(' ').append(numeric).append("] ");
                    }
                    case TEXT -> appendTextMatch(sb, field.name(), value);
                    // FtType 仅 TAG/NUMERIC/TEXT 三值；兜底防御未来新增枚举值时静默漏翻译
                    default -> throw new IllegalStateException("未支持的索引类型: " + indexType);
                }
            }
        }
        // ---------- 数值范围过滤 ----------
        if (!noRanges) {
            for (Map.Entry<String, RangeFilter> e : rangeFilters.entrySet()) {
                FieldSchema field = byName.get(e.getKey());
                // 范围只对 NUMERIC 索引有意义；TAG/TEXT 收到范围条件 → 降级
                if (field == null || indexTypeOf(field) != FtType.NUMERIC) {
                    return null;
                }
                RangeFilter range = e.getValue();
                String min = range.min() == null ? "-inf" : toNumericRangeValue(String.valueOf(range.min()));
                String max = range.max() == null ? "+inf" : toNumericRangeValue(String.valueOf(range.max()));
                if (min == null || max == null) {
                    return null;
                }
                sb.append('@').append(field.name()).append(":[").append(min).append(' ')
                        .append(max).append("] ");
            }
        }
        // ---------- TEXT 全文匹配 ----------
        if (!noTexts) {
            for (Map.Entry<String, String> e : textFilters.entrySet()) {
                FieldSchema field = byName.get(e.getKey());
                // 文本匹配只对 TEXT 索引有意义；其余形态 → 降级
                if (field == null || indexTypeOf(field) != FtType.TEXT) {
                    return null;
                }
                if (e.getValue() == null || e.getValue().isBlank()) {
                    // 空文本视同"该字段无过滤条件"，跳过而不是降级/全量
                    continue;
                }
                appendTextMatch(sb, field.name(), e.getValue());
            }
        }
        return sb.isEmpty() ? "*" : sb.substring(0, sb.length() - 1);
    }

    /** 追加 TAG 多值 OR 条件：{@code @f:{"v1"|"v2"}}。调用方保证值不含 null。 */
    private static void appendTagOr(StringBuilder sb, String fieldName, Collection<?> values) {
        sb.append('@').append(fieldName).append(":{");
        boolean first = true;
        for (Object v : values) {
            if (!first) {
                sb.append('|');
            }
            sb.append(quoteTag(String.valueOf(v)));
            first = false;
        }
        sb.append("} ");
    }

    /**
     * 追加 TEXT 分词匹配条件：{@code @f:(tok1 tok2)}，token 间隐式 AND。
     *
     * <p>按空白分词后逐 token 复用 {@link #quoteTag} 转义（引号包裹 +
     * 反斜杠转义），与 TAG 值同一套防注入规则。
     */
    private static void appendTextMatch(StringBuilder sb, String fieldName, String text) {
        sb.append('@').append(fieldName).append(":(");
        boolean first = true;
        for (String token : text.trim().split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (!first) {
                sb.append(' ');
            }
            sb.append(quoteTag(token));
            first = false;
        }
        sb.append(") ");
    }

    /**
     * TAG/TEXT 值转义：包双引号并转义内部的反斜杠与双引号。
     *
     * <p>只处理这两个字符即可：引号内的其他标点（空格/@/冒号等）由引号保护为字面量。
     */
    public static String quoteTag(String raw) {
        String escaped = raw.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    /**
     * 校验数值字段的过滤值可解析为数字。
     *
     * @return 规范化后的数值文本；解析失败返回 null
     */
    static String toNumericRangeValue(String raw) {
        try {
            // 统一走 Double 解析，覆盖 INT/LONG/DOUBLE；TIMESTAMP 存的是毫秒数字符串同样适用
            double d = Double.parseDouble(raw);
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            // 整数值去掉小数尾巴（3.0 -> 3），保持查询串可读
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                return Long.toString((long) d);
            }
            return raw;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

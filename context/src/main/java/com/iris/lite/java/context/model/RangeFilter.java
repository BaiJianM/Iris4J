package com.iris.lite.java.context.model;

import com.iris.lite.java.shared.error.ErrorCode;
import com.iris.lite.java.shared.error.IrisException;
import com.iris.lite.java.shared.time.UtcLiterals;

/**
 * 数值范围过滤条件。
 *
 * <p>两个端点都用 {@link Double} 装箱：<b>null 表示该端点开放</b>——
 * 只有 min 即 {@code >= min}，只有 max 即 {@code <= max}，两端都有即闭区间
 * {@code [min, max]}。翻译到 FT.SEARCH 时开放端点用 {@code -inf}/{@code +inf}。
 *
 * <p>TIMESTAMP/DATETIME 字段的范围过滤优先以<b>日期字面量字符串</b>传入
 * （{@link #parse} 统一按 UTC 字面换算毫秒）；
 * epoch 毫秒数字同样接受（向后兼容）——让 LLM 心算毫秒
 * 会把窗口两端各偏一天，日期字面量是 AI 调用方的首选形态。
 *
 * @param min 下界（含），null = 负无穷
 * @param max 上界（含），null = 正无穷
 */
public record RangeFilter(Double min, Double max) {

    /**
     * 从原始入参构造（AI 工具 / REST / MCP 各入口共用）。
     *
     * <p>每端点接受：{@code null}（开放）、{@link Number}（epoch 毫秒，直取）、
     * {@link String}（日期/日期时间字面量，{@link UtcLiterals} 按 UTC 字面换算；
     * 日期-only 作 max 端点自动覆盖当日全天）。其他类型报 INVALID_QUERY。
     *
     * @param rawMin 下界原始值
     * @param rawMax 上界原始值
     * @return 规范化的范围过滤
     */
    public static RangeFilter parse(Object rawMin, Object rawMax) {
        return new RangeFilter(toEndpoint(rawMin, false), toEndpoint(rawMax, true));
    }

    /** 单端点规范化：null 开放、Number 直取、String 走 UTC 字面换算。 */
    private static Double toEndpoint(Object raw, boolean maxEndpoint) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s && !s.isBlank()) {
            return (double) UtcLiterals.toEpochMilli(s, maxEndpoint);
        }
        throw new IrisException(ErrorCode.INVALID_QUERY,
                "范围过滤端点类型非法（应为数字毫秒或日期字符串）: " + raw);
    }

    /** 紧凑构造器：两端都传时校验 min <= max，倒置直接报错（静默交换会掩盖调用方笔误）。 */
    public RangeFilter {
        if (min != null && max != null && min > max) {
            throw new IrisException(ErrorCode.INVALID_QUERY,
                    "范围过滤 min > max: " + min + " > " + max);
        }
    }

    @Override
    public String toString() {
        return "[" + (min == null ? "-inf" : min) + ", " + (max == null ? "+inf" : max) + "]";
    }
}

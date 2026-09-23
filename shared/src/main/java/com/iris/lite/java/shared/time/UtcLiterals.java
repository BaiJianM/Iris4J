package com.iris.lite.java.shared.time;

import com.iris.lite.java.shared.error.ErrorCode;
import com.iris.lite.java.shared.error.IrisException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * DATETIME 字面量 ⇄ epoch 毫秒换算的<b>唯一出处</b>（时间标准的代码层收口）。
 *
 * <p><b>标准铁律</b>：源库 DATETIME 经 Debezium 投影后的毫秒值恒为
 * 「字面量按 UTC 解释」，且无法通过任何配置改变。因此这里的一切换算<b>缺省按
 * UTC 字面</b>——不取系统默认时区（取了就会在 +08:00 宿主机上整体偏移 8 小时）。
 *
 * <p><b>为什么这个类必须存在</b>：AI 调用方（Agent / MCP 客户端）传 range 过滤时
 * 让模型自己心算 epoch 毫秒是错的——epoch 心算是确定性计算，确定性计算必须由
 * 代码完成，LLM 只负责表达意图（ISO 日期字符串）。本类把「表达」与「换算」
 * 分离：任何入口（REST / MCP / Agent 工具）收到日期字符串都走这里，标准一处收口。
 *
 * <p><b>接受格式</b>：
 * <ul>
 *   <li>{@code yyyy-MM-dd}——日期；作 min 端点 = 当日 00:00:00.000，
 *       作 max 端点 = 当日 23:59:59.999（闭区间语义下覆盖全天）；</li>
 *   <li>{@code yyyy-MM-dd[ T]HH:mm:ss[.SSS]}——日期时间，{@code T} 或空格分隔均可；</li>
 *   <li>带显式时区后缀（{@code Z} / {@code +08:00}）——尊重显式时区换算；
 *       缺省（无后缀）即 UTC 字面。</li>
 * </ul>
 *
 * <p>不可解析时抛 {@link IrisException}{@code INVALID_QUERY}——消息面向 AI 调用方
 * 可读（模型看到错误能自行修正重试），不吞异常返回 null（静默换 null 会把
 * 「写错格式」变成「窗口偏移」，比失败更危险）。
 */
public final class UtcLiterals {

    /** 工具类禁止实例化。 */
    private UtcLiterals() {
    }

    /**
     * 把日期/日期时间字面量解析为 epoch 毫秒。
     *
     * @param raw         字面量（见类注释的接受格式）
     * @param maxEndpoint 是否为闭区间的上界端点——仅影响日期-only 写法：
     *                    true 时归整到当日 23:59:59.999，使 max 日期字面量
     *                    覆盖当日全天而非恰好第一毫秒
     * @return epoch 毫秒
     * @throws IrisException INVALID_QUERY 格式不可解析
     */
    public static long toEpochMilli(String raw, boolean maxEndpoint) {
        if (raw == null || raw.isBlank()) {
            throw invalid(raw, "空白");
        }
        String s = raw.trim().replace(' ', 'T');
        try {
            // 显式时区后缀：Z 或 ±HH:MM / ±HHMM —— 尊重调用方声明的时区
            if (s.endsWith("Z") || s.endsWith("z")
                    || s.matches(".*[+-]\\d{2}:?\\d{2}(?:\\.\\d+)?$")) {
                return OffsetDateTime.parse(s).toInstant().toEpochMilli();
            }
            // 日期-only：min = 当日 00:00:00.000；max = 当日 23:59:59.999
            if (s.length() == 10) {
                LocalDate d = LocalDate.parse(s);
                if (maxEndpoint) {
                    return d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1;
                }
                return d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            }
            // 日期时间：ISO_LOCAL_DATE_TIME 覆盖秒可选/毫秒可选两种形态
            LocalDateTime ldt = LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
            throw invalid(raw, e.getMessage());
        }
    }

    private static IrisException invalid(String raw, String reason) {
        return new IrisException(ErrorCode.INVALID_QUERY,
                "日期字面量不可解析（接受 yyyy-MM-dd / yyyy-MM-dd HH:mm:ss，"
                        + "缺省按 UTC 字面解释）: " + raw + " — " + reason);
    }
}

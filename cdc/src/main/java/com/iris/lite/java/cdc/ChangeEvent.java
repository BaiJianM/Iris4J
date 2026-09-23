package com.iris.lite.java.cdc;

import java.util.Map;

/**
 * Debezium 变更事件（envelope 解析结果），见方案 4.2。
 *
 * <p><b>envelope 结构</b>：Debezium 输出的 JSON 形如
 * {@code {"before": {...}, "after": {...}, "op": "u", "ts_ms": ...}}。
 * 这里只取三个字段——其余（source、ts_ms 等）首期用不上，
 * 不建模可以避免 Debezium 版本升级时字段变化带来的解析脆弱性。
 *
 * @param op     Debezium 操作类型：c(insert)/u(update)/d(delete)/r(snapshot read)
 * @param before 变更前行数据，delete 时为旧行，其余为 null
 * @param after  变更后行数据，delete 时为 null
 */
public record ChangeEvent(String op, Map<String, Object> before, Map<String, Object> after) {
}

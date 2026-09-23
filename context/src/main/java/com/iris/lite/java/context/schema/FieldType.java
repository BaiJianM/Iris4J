package com.iris.lite.java.context.schema;

/**
 * 字段类型。用于 Schema 定义与结果类型判断，不做严格类型校验。
 *
 * <p><b>为什么不全做严格校验</b>：源库经 Debezium 过来的值类型已经确定，
 * 在 Redis 侧再校验一遍属于重复劳动；只需要"STRING vs 非 STRING"这一条分界线，
 * 用于语义缓存判断哪些值可以模糊匹配。
 *
 * <p>枚举值与 YAML 中的 {@code type:} 字段严格对应（大小写敏感），
 * YamlSchemaProvider 用 {@code FieldType.valueOf} 解析，写错会在加载期直接报错。
 */
public enum FieldType {
    /** 字符串。唯一参与语义（模糊）匹配的字段类型。 */
    STRING,
    /** 整数。精确匹配。 */
    INT,
    /** 长整数。精确匹配。 */
    LONG,
    /** 浮点数。精确匹配。 */
    DOUBLE,
    /** 布尔。精确匹配。 */
    BOOLEAN,
    /** 时间戳。精确匹配。 */
    TIMESTAMP
}

package com.iris4j.shared.error;

/**
 * iris4j 统一错误码。
 *
 * <p><b>契约意义</b>：所有模块对外返回的错误都使用这里定义的稳定错误码，
 * 禁止各自散落字符串。Agent（MCP 客户端）和人（REST 调用方）都靠 code 做分支处理，
 * 而不是靠解析中文消息文本。
 *
 * <p><b>分层</b>：错误码只承载 code 与默认消息，不绑定任何 HTTP 传输细节；
 * HTTP 状态映射由 api 层的 {@code GlobalExceptionHandler} 负责，
 * 这样 MCP 入口复用同一套错误语义时不会被 HTTP 状态码污染。
 *
 * <p><b>号段约定</b>：
 * <ul>
 *   <li>10xx = 查询与实体相关（参数/字段/租户）</li>
 *   <li>20xx = CDC 相关</li>
 *   <li>30xx = 鉴权相关</li>
 *   <li>40xx = api 层本地错误（如 ApiKeyAuthFilter 的 IRIS-4010）</li>
 *   <li>9999 = 兜底未知错误</li>
 * </ul>
 */
public enum ErrorCode {

    /** 实体不存在。主键精确查询无结果时抛出，映射 HTTP 404。 */
    ENTITY_NOT_FOUND("IRIS-1001", "实体不存在"),

    /** 字段不存在。请求的 fields 中出现 Schema 未声明的字段，映射 HTTP 400。 */
    FIELD_NOT_FOUND("IRIS-1002", "字段不存在"),

    /** 查询请求非法（参数缺失、分页超限等），映射 HTTP 400。 */
    INVALID_QUERY("IRIS-1003", "查询请求非法"),

    /** 多租户实体查询缺少租户上下文，映射 HTTP 400。 */
    TENANT_REQUIRED("IRIS-1004", "该实体为多租户实体，请求缺少 tenant"),

    /**
     * 显式租户过滤条件与 tenant 参数冲突（疑似越权伪造），映射 HTTP 403。
     *
     * <p>例如请求 {@code tenant=t1} 但 filters 里写了 {@code tenant_id=t2}，
     * 说明调用方试图绕过租户隔离，直接拒绝而不是"以某个为准"。
     */
    TENANT_MISMATCH("IRIS-1005", "租户条件与过滤条件冲突"),

    /** CDC 事件消费失败，映射 HTTP 500。 */
    CDC_CONSUME_FAILED("IRIS-2001", "CDC 事件消费失败"),

    /** 未授权，映射 HTTP 403。 */
    UNAUTHORIZED("IRIS-3001", "未授权"),

    /** 未知内部错误，映射 HTTP 500。兜底用，出现即说明有未预期的异常路径。 */
    INTERNAL_ERROR("IRIS-9999", "内部错误");

    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    /** 稳定错误码（如 IRIS-1001），供调用方做分支判断。 */
    public String code() {
        return code;
    }

    /** 默认人类可读消息，构造异常时未指定消息则用它兜底。 */
    public String defaultMessage() {
        return defaultMessage;
    }
}

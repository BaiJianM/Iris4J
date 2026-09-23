package com.iris.lite.java.shared.error;

/**
 * redis-iris-java 统一业务异常。
 *
 * <p><b>为什么 REST 与 MCP 共用同一个异常类型</b>：两个入口暴露的是同一批能力，
 * 错误语义必须一致——否则同一个"实体不存在"的错误，REST 返回 IRIS-1001/404、
 * MCP 却返回一句自由文本，调用方就得写两套解析逻辑。
 * 传输层差异（HTTP 状态码 vs MCP isError 标志）由各自的适配层负责翻译。
 *
 * <p>携带稳定错误码 {@link ErrorCode}。具体错误可继承本类细化；
 * 多数场景直接用构造器携带补充信息即可（补充信息写中文，面向排障的人）。
 */
public class IrisException extends RuntimeException {

    private final ErrorCode errorCode;

    /** 仅带错误码，消息取 {@link ErrorCode#defaultMessage()}。 */
    public IrisException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    /**
     * 错误码 + 补充消息。
     *
     * <p>补充消息要写清"哪个实体/哪个字段/哪个值"，例如
     * {@code "字段不存在: mobile"}，而不是笼统的"参数错误"。
     */
    public IrisException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 错误码 + 补充消息 + 根因。
     *
     * <p>保留 cause 是为了让 api 层能打印完整堆栈定位底层故障
     * （如 Redis 连接失败被包装成业务异常时）。
     */
    public IrisException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** 结构化错误码枚举，供 api 层做 HTTP 状态映射。 */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 字符串错误码（如 IRIS-1001），供响应体输出。等价于 {@code errorCode().code()}。 */
    public String code() {
        return errorCode.code();
    }
}

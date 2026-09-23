package com.iris4j.api.web;

import com.iris4j.shared.error.ErrorCode;
import com.iris4j.shared.error.IrisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器：把统一错误码映射为 HTTP 状态。
 *
 * <p><b>为什么映射在这里而不是 shared 层</b>：shared 层不绑 HTTP——
 * MCP 入口复用同一套错误语义，但不使用 HTTP 状态码。
 * 把映射集中在这里，保证 REST 与 MCP 的错误码一致、只有传输层表现不同。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常：按错误码映射 HTTP 状态。
     *
     * <p>不打 error 日志——业务异常（实体不存在、字段不存在、租户冲突）
     * 是预期的错误分支，不是系统故障。用 debug 记录即可，
     * 否则一次错误查询就会在日志里留一条 ERROR，掩盖真正的问题。
     */
    @ExceptionHandler(IrisException.class)
    public ResponseEntity<ErrorResponse> handleIris(IrisException e) {
        HttpStatus status = toHttpStatus(e.errorCode());
        log.debug("业务异常 code={} status={} message={}", e.code(), status.value(), e.getMessage(), e);
        return ResponseEntity.status(status)
                .body(new ErrorResponse(e.code(), e.getMessage()));
    }

    /**
     * 参数异常：统一按 400 返回。
     *
     * <p>主要来自各 record 紧凑构造器的 {@code IllegalArgumentException}
     * （如 EntityKey 三元组为空、FieldType.valueOf 解析失败）。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.debug("参数异常 message={}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ErrorCode.INVALID_QUERY.code(), e.getMessage()));
    }

    /**
     * 路径不存在：按 404 返回而非落入兜底 500。
     *
     * <p>中间件本体不注册演示用的 /api/v1/agent/* 路径，
     * 演示端点请求会以 NoResourceFoundException 走到兜底——它不是系统故障，
     * 打 ERROR 日志会造成「中间件异常」的假象。
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        log.debug("路径不存在: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ErrorCode.ENTITY_NOT_FOUND.code(), "接口不存在: " + e.getResourcePath()));
    }

    /**
     * 兜底：未预期的异常。
     *
     * <p><b>这里必须打 error 并带堆栈</b>——走到这里说明有代码路径没被正确覆盖，
     * 是需要修的 bug，而不是用户输入问题。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(ErrorCode.INTERNAL_ERROR.code(), ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }

    /**
     * 错误码 -> HTTP 状态映射。
     *
     * <p>switch 是穷举的（无 default 分支），新增 {@link ErrorCode} 枚举值时
     * 编译器会直接报错，强制你决定它的 HTTP 语义——避免新错误码被静默映射成 500。
     */
    private HttpStatus toHttpStatus(ErrorCode code) {
        return switch (code) {
            case ENTITY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAUTHORIZED, TENANT_MISMATCH -> HttpStatus.FORBIDDEN;
            case FIELD_NOT_FOUND, INVALID_QUERY, TENANT_REQUIRED -> HttpStatus.BAD_REQUEST;
            case CDC_CONSUME_FAILED, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}

package com.iris4j.shared.model;

import com.iris4j.shared.error.ErrorCode;
import com.iris4j.shared.error.IrisException;

/**
 * 分页请求。固定分页上限，超限即拒绝。
 *
 * <p><b>为什么要硬上限</b>：等值过滤走 SCAN + 内存匹配，pageSize 直接决定
 * 单次查询要 JSON.GET 并反序列化多少条投影。不设上限时一个
 * {@code pageSize=1000000} 的请求就能把 Redis 连接和堆内存打满。
 * 上限写死在 {@link #MAX_PAGE_SIZE}（200），调用方无法绕过。
 *
 * @param page     页码，从 1 开始
 * @param pageSize 每页大小，1..{@link #MAX_PAGE_SIZE}
 */
public record PageRequest(int page, int pageSize) {

    /** 固定分页上限。 */
    public static final int MAX_PAGE_SIZE = 200;

    /** 缺省页码。 */
    public static final int DEFAULT_PAGE = 1;

    /** 缺省每页大小。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * 紧凑构造器：严格校验，越界直接抛业务异常。
     *
     * <p>抛 {@link IrisException} 而非 {@link IllegalArgumentException}，
     * 是为了让 api 层的 {@code GlobalExceptionHandler} 统一映射成
     * 400 + 稳定错误码 IRIS-1003。
     */
    public PageRequest {
        if (page < 1) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "page 必须 >= 1");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IrisException(
                    ErrorCode.INVALID_QUERY, "pageSize 必须在 1.." + MAX_PAGE_SIZE + " 之间");
        }
    }

    /**
     * 宽松构造：非法值回落默认值，而非抛异常。
     *
     * <p>用于"调用方没传就用默认"的场景（如 MCP 工具参数缺省）。
     * 与紧凑构造器的区别是设计意图不同：显式传了非法值要报错（紧凑构造器），
     * 没传则取默认（本方法）。
     */
    public static PageRequest of(int page, int pageSize) {
        return new PageRequest(
                page < 1 ? DEFAULT_PAGE : page,
                pageSize < 1 ? DEFAULT_PAGE_SIZE : pageSize);
    }
}

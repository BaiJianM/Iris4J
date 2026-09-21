package com.iris.lite.api.dto;

/**
 * 缓存写入请求体。value 为任意 JSON 值（对象/数组/标量），
 * 故用 {@code Object} 接收——缓存层存的是黑盒，不做结构约束。
 */
public record PutCacheRequest(
        String namespace,
        String key,
        Object value,
        long ttlSeconds) {
}

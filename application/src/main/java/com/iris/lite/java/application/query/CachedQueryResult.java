package com.iris.lite.java.application.query;

import java.util.List;
import java.util.Map;

/**
 * 查询结果缓存值对象（具体类型，便于 Jackson 直接反序列化，无需 TypeReference）。
 *
 * <p>对应 {@code Page<Map<String,Object>>} 的扁平化存储。
 *
 * <p><b>为什么不用泛型 Page 直接序列化</b>：Jackson 反序列化泛型需要
 * {@code TypeReference} 保留类型信息，而缓存层的值是 {@code Object}，
 * 拿不到具体类型。用具体 record 可以直接 {@code readValue(json, CachedQueryResult.class)}，
 * 简单且不易出错。
 */
public record CachedQueryResult(
        List<Map<String, Object>> items,
        Long total,
        int page,
        int pageSize) {
}

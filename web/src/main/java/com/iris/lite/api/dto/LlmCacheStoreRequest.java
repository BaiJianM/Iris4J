package com.iris.lite.api.dto;

import java.util.List;

/**
 * LLM 响应缓存写入请求。
 *
 * @param namespace  命名空间
 * @param prompt     触发响应的 prompt
 * @param response   LLM 响应
 * @param model      模型名，可空
 * @param ttlSeconds 过期秒数，null 用服务端配置（默认 3600）
 * @param dependencies 依赖实体名列表（同 namespace，可空）。声明后写入时记录各实体
 *                     当前数据版本，命中即校验——数据变更过的条目自动过期（数据版本围栏）
 */
public record LlmCacheStoreRequest(
        String namespace,
        String prompt,
        String response,
        String model,
        Long ttlSeconds,
        List<String> dependencies) {
}

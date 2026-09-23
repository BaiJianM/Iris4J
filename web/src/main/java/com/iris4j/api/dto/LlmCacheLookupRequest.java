package com.iris4j.api.dto;

/**
 * LLM 响应缓存查找请求。
 *
 * @param namespace         命名空间（缓存隔离边界）
 * @param prompt            用户 prompt
 * @param model             模型名，可空（参与精确过滤，不参与语义匹配）
 * @param thresholdOverride 单次查找阈值覆盖（0-1），null 用服务端配置
 * @param fresh             实时性 bypass：true 跳过缓存直接返回未命中，
 *                          供实时敏感问题主动绕过语义缓存；null 视为 false
 */
public record LlmCacheLookupRequest(
        String namespace,
        String prompt,
        String model,
        Double thresholdOverride,
        Boolean fresh) {
}

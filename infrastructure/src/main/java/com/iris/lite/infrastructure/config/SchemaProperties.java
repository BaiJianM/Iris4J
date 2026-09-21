package com.iris.lite.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 动态 Schema 配置模型，前缀 {@code iris.schema}。
 *
 * <p><b>热加载开关的双重条件</b>：实际生效需要
 * {@code hotReload=true} <b>且</b> {@code dir} 非空——
 * 只开开关不配目录等于没开（见 {@code YamlSchemaProvider#hasExternalDir}）。
 *
 * @param hotReload      是否启用外部目录热加载
 * @param dir            外部 Schema 目录（绝对或相对路径，留空则仅用 classpath）
 * @param pollIntervalMs 轮询文件变更间隔（毫秒）
 */
@ConfigurationProperties(prefix = "iris.schema")
public record SchemaProperties(
        boolean hotReload,
        String dir,
        long pollIntervalMs) {

    /**
     * 紧凑构造器：dir 为 null 归一化为 ""（下游可无脑 isBlank），
     * 轮询间隔非法时回落 5 秒。
     */
    public SchemaProperties {
        if (dir == null) {
            dir = "";
        }
        if (pollIntervalMs <= 0) {
            pollIntervalMs = 5000;
        }
    }
}

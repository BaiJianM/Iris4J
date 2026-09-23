/*
 * redis-iris-java 基础设施适配层。
 *
 * 内容：Redis 客户端装配与命令适配器（RedisConfig / RedisAdapter）、
 * 各仓储的 Lettuce 实现、YAML Schema 提供者、Bm25Embedder、配置属性模型。
 *
 * 隔离原则：Lettuce / SnakeYAML 等第三方类型只允许在本模块出现，
 * 对外暴露的必须是业务抽象（KeyStrategy / RedisAdapter / 各仓储端口实现）。
 */
package com.iris.lite.java.infrastructure;

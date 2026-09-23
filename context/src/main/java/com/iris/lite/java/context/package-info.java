/*
 * redis-iris-java 上下文模块（领域端口层）。
 *
 * 内容：查询模型（QueryRequest）、实体投影模型（EntityProjection）、
 * Schema 模型与端口（EntitySchema / SchemaProvider / SchemaManager）、
 * 投影仓储端口（EntityProjectionRepository）。
 *
 * 只定义接口与模型，实现在 infrastructure 层——查询链路不感知 Redis 细节。
 */
package com.iris.lite.java.context;

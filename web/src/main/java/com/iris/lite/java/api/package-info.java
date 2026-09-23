/*
 * redis-iris-java 接口适配与启动模块。
 *
 * 包结构：
 * - 根包：Spring Boot 启动入口（RedisIrisJavaApplication）；
 * - controller：REST Controller（13 个，按端点族拆分）；
 * - config：@Configuration 装配类（指标门面接线等）；
 * - dto：REST 请求体 record；
 * - mcp：MCP 工具（静态 + 动态注册）与 MCP 面守卫；
 * - security：Agent 身份、key 注册表、鉴权过滤器、安全配置；
 * - web：Web 层横切（全局异常处理与统一错误体、健康指示器）；
 * - lifecycle：生命周期守护（关闭看门狗、启动安全警告）。
 *
 * 硬约束：api 层只能调 application 层，不得直接访问 Redis 或仓储实现。
 */
package com.iris.lite.java.api;

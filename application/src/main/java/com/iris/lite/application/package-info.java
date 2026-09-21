/*
 * iris-lite 用例编排层。
 *
 * 按业务子域分包：
 * - agent：Agent 会话编排、工具目录/分发、agent key 管理（8+3 类）；
 * - query：实体查询治理链（装饰器 AccessControlled→语义缓存→精确缓存→Query Engine）、
 *   关联查询、版本守卫、索引就绪、批投影端口、配方草稿（14 类）；
 * - cache：精确缓存与 LLM 语义缓存应用服务、重嵌入迁移（4 类）；
 * - memory：记忆应用服务、自动抽取、会话摘要（4 类）；
 * - schema：Schema 管理与外部文件写回（3 类）；
 * - ops：Redis 运维/洞察、CDC 洞察、DLQ 管理、一致性校验（7 类）；
 * - rerank：交叉编码器重排端口（1 类）。
 *
 * 依赖方向：只依赖 context / shared 定义的端口接口，
 * 不依赖任何 Redis 客户端与 HTTP/MCP 传输细节。
 */
package com.iris.lite.application;

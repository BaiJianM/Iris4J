/*
 * iris4j 共享内核层。
 *
 * 内容：公共模型（EntityKey / Page / PageRequest）、统一异常与错误码、
 * Redis key 生成策略（KeyStrategy / RedisKeyPatterns）。
 *
 * 依赖方向：不依赖任何其他模块，可被所有模块依赖。
 * key 生成策略放这里，是为了让 cdc（写侧）与 context（读侧）用同一份 key 规则。
 */
package com.iris4j.shared;

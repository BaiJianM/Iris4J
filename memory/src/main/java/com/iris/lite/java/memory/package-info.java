/*
 * redis-iris-java 记忆模块（端口层）。
 *
 * 内容：工作记忆与长期记忆的领域模型（WorkingMemoryEntry / LongTermMemory）、
 * 仓储端口（MemoryRepository）、自动抽取预留接口（MemoryExtractor）。
 *
 * 自动抽取默认关闭，不注入实现即不产生任何 LLM 调用。
 */
package com.iris.lite.java.memory;

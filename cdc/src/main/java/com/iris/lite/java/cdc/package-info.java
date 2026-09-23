/*
 * redis-iris-java CDC 模块。
 *
 * 内容：Redis Stream 消费（CdcConsumer）、变更事件模型与处理器、DLQ 管理实现。
 *
 * 职责边界：只负责事件消费与投影，不对外提供查询能力。
 * 每个数据源一个消费线程、独占一条 Redis 连接（阻塞命令必须独占连接）。
 */
package com.iris.lite.java.cdc;

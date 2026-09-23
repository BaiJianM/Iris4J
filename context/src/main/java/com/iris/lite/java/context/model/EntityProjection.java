package com.iris.lite.java.context.model;

import com.iris.lite.java.shared.model.EntityKey;

import java.util.Map;

/**
 * 实体投影：RedisJSON 中存储的一行实体的字段映射。
 *
 * <p><b>投影的含义</b>：源库（MySQL/PostgreSQL）的一行数据，经 Debezium 采集、
 * redis-iris-java 消费后，在 Redis 里以 JSON 文档形式存在的一份副本。
 * 它是"查询侧唯一的数据来源"——查询不回源库，只读投影。
 *
 * <p><b>值为原始值、不做类型转换</b>：data 里的值保持 Debezium envelope 给出的原始形态
 * （数字可能是 Integer/Long，时间可能是字符串或数字）。类型转换留到 api 层序列化时由
 * Jackson 处理。中途擅自转换会引入精度丢失和格式歧义。
 *
 * @param key  实体唯一键（决定这条投影落在哪个 Redis key 上）
 * @param data 字段名 -> 字段值（原始值，不做类型转换）
 */
public record EntityProjection(EntityKey key, Map<String, Object> data) {

    /**
     * 紧凑构造器：data 为 null 时归一化为不可变空 Map。
     *
     * <p>归一化的意义：下游 {@code project()} 裁剪字段、{@code matches()} 匹配过滤
     * 都可以直接遍历 data，不必到处判空。空投影（如 delete 事件的残留）是合法状态。
     */
    public EntityProjection {
        if (data == null) {
            data = Map.of();
        }
    }
}

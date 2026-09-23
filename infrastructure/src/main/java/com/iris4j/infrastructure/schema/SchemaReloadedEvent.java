package com.iris4j.infrastructure.schema;

import com.iris4j.context.schema.EntitySchema;

import java.util.List;

/**
 * Schema 热重载完成事件。
 *
 * <p>由 {@link YamlSchemaProvider#reload()} 在外部 Schema 原子替换成功后发布，
 * 携带重载后的<b>全量生效 Schema</b>（classpath 基线 + 外部覆盖合并）。
 * 当前消费者是 {@code EntityIndexManager}：索引定义派生自 Schema 的 indexed 声明，
 * Schema 变了索引就必须同步重建，二者靠这个事件解耦——Schema 层不感知索引的存在，
 * 索引层不侵入解析过程。
 *
 * @param schemas 重载后生效的全部 Schema（不可变列表）
 */
public record SchemaReloadedEvent(List<EntitySchema> schemas) {

    public SchemaReloadedEvent {
        schemas = schemas == null ? List.of() : List.copyOf(schemas);
    }
}

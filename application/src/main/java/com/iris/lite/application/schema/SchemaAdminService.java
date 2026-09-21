package com.iris.lite.application.schema;

import com.iris.lite.context.schema.SchemaStatus;

/**
 * Schema 管理服务（动态 Schema），REST 与 MCP 共用。
 *
 * <p>基础动作：强制重载与查状态。
 *
 * <p>关系映射编辑：修改字段的 {@code relatedEntity} 声明并回盘 YAML
 * 后热载，支持界面侧「Related Entity 下拉框手动选择」；
 * 其余 Schema 内容（字段增删/索引声明等）仍走文件驱动路线，不在运行时 API 范围内。
 */
public interface SchemaAdminService {

    /** 强制重载外部 Schema；解析失败时保留旧 Schema，不抛异常。 */
    void reload();

    /** 查询热加载状态（是否启用、目录、实体数、最近加载时间、最近错误）。 */
    SchemaStatus status();

    /**
     * 更新字段的 relatedEntity 映射。
     *
     * <p>{@code relatedEntity} 传 null/空白 = 清除映射（保留原索引声明）。
     * 设非空时：目标实体须同 namespace 存在（否则 404）；FK 索引缺失时按字段类型
     * 自动补显式索引（数值型 numeric，否则 tag），结果以 {@code indexAutoAssigned}
     * 告知调用方。写盘成功后立即 reload 热载；reload 失败（写盘损坏等）旧 Schema
     * 保留生效，抛异常并由 status().lastError 上浮原因。
     *
     * @return 更新结果（含是否自动补了索引）
     * @throws com.iris.lite.shared.error.IrisException 实体/字段/目标实体不存在（404）、
     *         回盘或热载失败（500）
     */
    RelatedEntityUpdateResult updateRelatedEntity(String namespace, String entity,
                                                  String fieldName, String relatedEntity);

    /** 关系映射更新结果。 */
    record RelatedEntityUpdateResult(
            String namespace,
            String entity,
            String field,
            String relatedEntity,
            boolean indexAutoAssigned,
            String assignedIndex) {
    }
}

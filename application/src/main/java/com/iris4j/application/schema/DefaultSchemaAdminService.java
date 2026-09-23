package com.iris4j.application.schema;

import com.iris4j.context.schema.EntitySchema;
import com.iris4j.context.schema.FieldSchema;
import com.iris4j.context.schema.FieldType;
import com.iris4j.context.schema.SchemaManager;
import com.iris4j.context.schema.SchemaStatus;
import com.iris4j.shared.error.ErrorCode;
import com.iris4j.shared.error.IrisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * Schema 管理服务默认实现：reload/status 委托 {@link SchemaManager}；
 * 关系映射编辑在此编排「校验 → 重组 Schema → 回盘 → 热载 → 复核」。
 *
 * <p><b>编排语义</b>：
 * <ul>
 *   <li>校验全部前置（实体/字段/目标实体存在性），任何一步不过都不产生写盘副作用；</li>
 *   <li>FK 强制索引由 {@link EntitySchema} 构造器兜底（fail-fast），但这里提前
 *       按类型自动补显式索引，让控制台一步完成而不是报错让用户先去加索引；</li>
 *   <li>回盘成功后立即 {@code reload()}，并<b>复核生效结果</b>——reload 内部
 *       "解析失败保留旧 Schema" 不抛异常，必须显式确认新映射真的生效了，
 *       否则调用方会以为改成了实际却在用旧 Schema（假成功比失败更危险）。</li>
 * </ul>
 */
@Service
public class DefaultSchemaAdminService implements SchemaAdminService {

    private static final Logger log = LoggerFactory.getLogger(DefaultSchemaAdminService.class);

    private final SchemaManager schemaManager;
    private final SchemaFileWriter schemaFileWriter;

    public DefaultSchemaAdminService(SchemaManager schemaManager, SchemaFileWriter schemaFileWriter) {
        this.schemaManager = schemaManager;
        this.schemaFileWriter = schemaFileWriter;
    }

    /**
     * 强制重载外部 Schema。
     *
     * <p>重载失败不会抛到调用方（{@code YamlSchemaProvider} 内部保留旧 Schema），
     * 调用方应通过 {@link #status()} 的 lastError 判断结果。
     */
    @Override
    public void reload() {
        log.debug("Schema 重载请求");
        schemaManager.reload();
    }

    /** 查询热加载状态。 */
    @Override
    public SchemaStatus status() {
        return schemaManager.status();
    }

    /** 关系映射更新：校验链 + FK 索引自动补 + 回盘热载 + 复核。 */
    @Override
    public RelatedEntityUpdateResult updateRelatedEntity(String namespace, String entity,
                                                         String fieldName, String relatedEntity) {
        if (namespace == null || namespace.isBlank() || entity == null || entity.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "namespace 与 entity 不能为空");
        }
        if (fieldName == null || fieldName.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "fieldName 不能为空");
        }
        // 1) 源实体与字段存在性（get 未命中抛 ENTITY_NOT_FOUND 404）
        EntitySchema schema = schemaManager.get(namespace, entity);
        FieldSchema field = schema.fields().stream()
                .filter(f -> f.name().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new IrisException(ErrorCode.FIELD_NOT_FOUND,
                        "字段不存在: " + namespace + "/" + entity + "." + fieldName));

        // 2) 目标值归一化：null/空白 = 清除映射
        String target = relatedEntity == null || relatedEntity.isBlank()
                ? null : relatedEntity.trim();
        boolean autoAssigned = false;
        String assignedIndex = null;
        if (target != null) {
            // 3) 目标实体存在性预检（加载期不校验关系指向，运行期才爆——编辑时提前拦）
            schemaManager.get(namespace, target);
            // 4) FK 强制索引：缺索引时按类型自动补（数值型 numeric，其余 tag），
            //    与 FieldSchema.effectiveIndex() 的推导规则一致
            if (field.effectiveIndex() == null) {
                assignedIndex = field.type() == FieldType.STRING || field.type() == FieldType.BOOLEAN
                        ? "tag" : "numeric";
                autoAssigned = true;
            }
        }

        // 5) 重组 Schema：仅替换目标字段，其余原样透传（含 description/values——
        //    值域语义是 Schema 的一部分，编辑关系映射不得静默洗掉）
        FieldSchema updated = new FieldSchema(field.name(), field.type(), field.indexed(),
                field.tags(), autoAssigned ? assignedIndex : field.index(),
                field.description(), target, field.values());
        EntitySchema rebuilt = new EntitySchema(schema.namespace(), schema.entity(),
                schema.primaryKeys(),
                schema.fields().stream().map(f -> f.name().equals(fieldName) ? updated : f).toList(),
                schema.tenantField(), schema.accessTagField(), schema.description());

        // 6) 回盘 + 热载
        String dir = schemaManager.status().schemaDir();
        try {
            schemaFileWriter.write(rebuilt, dir);
        } catch (IOException e) {
            throw new IrisException(ErrorCode.INTERNAL_ERROR,
                    "Schema 回盘失败（" + dir + "）: " + e.getMessage());
        }
        schemaManager.reload();

        // 7) 复核：reload 失败会静默保留旧 Schema，必须显式确认新映射已生效
        FieldSchema effective = schemaManager.get(namespace, entity).fields().stream()
                .filter(f -> f.name().equals(fieldName))
                .findFirst()
                .orElse(field);
        if (!java.util.Objects.equals(effective.relatedEntity(), target)) {
            throw new IrisException(ErrorCode.INTERNAL_ERROR,
                    "Schema 热载未生效（已保留旧 Schema），请查看 GET /api/v1/schema/status 的 lastError");
        }
        log.info("关系映射已更新 {}/{}.{} -> {}（indexAutoAssigned={}）",
                namespace, entity, fieldName, target == null ? "<清除>" : target, autoAssigned);
        return new RelatedEntityUpdateResult(namespace, entity, fieldName, target,
                autoAssigned, autoAssigned ? assignedIndex : null);
    }
}

package com.iris.lite.application.query;

import com.iris.lite.context.model.QueryRequest;
import com.iris.lite.context.schema.EntitySchema;
import com.iris.lite.context.schema.FieldSchema;
import com.iris.lite.context.schema.SchemaManager;
import com.iris.lite.context.schema.SchemaProvider;
import com.iris.lite.shared.error.ErrorCode;
import com.iris.lite.shared.error.IrisException;
import com.iris.lite.shared.model.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨实体关系导航服务。
 *
 * <p><b>关系图完全由 Schema 推导</b>：实体 A 的字段声明 {@code relatedEntity: B}
 * 即构成 A→B 的外键关系。导航支持两个方向，无需在目标实体侧重复声明：
 * <ul>
 *   <li><b>正向（多对一）</b>：给定 order 主键值，取 order.customer_id 指向的
 *       customer 行——查询 {@code customer WHERE pk = fk值}；</li>
 *   <li><b>反向（一对多）</b>：给定 customer 主键值，枚举 namespace 内所有
 *       指向 customer 的 FK 声明（如 order.customer_id），查询
 *       {@code order WHERE customer_id = 主键值}。</li>
 * </ul>
 *
 * <p><b>治理链复用（关键设计）</b>：所有行取回都走装配好的
 * {@link EntityQueryService} 链头（access-controlled → semantic → exact → default），
 * 因此租户隔离、access tags 行级/字段级裁剪、fail-closed、索引下推、
 * 缓存装饰对关系查询<b>自动生效</b>——关系导航不是绕过治理的旁路，
 * 只是"由服务端代填 filters"的普通查询。
 *
 * <p><b>为什么在服务层组装而不是加新查询语法</b>：QueryRequest 的 filters 语义
 * 保持简单（等值/范围/文本）；关系是 Schema 层的元知识，由本服务把它翻译成
 * 一次主键查询 + N 次索引过滤查询。N 是 FK 声明数（个位数），不产生 N+1 风暴。
 */
@Service
public class RelatedEntityService {

    private static final Logger log = LoggerFactory.getLogger(RelatedEntityService.class);

    private final EntityQueryService queryService;
    private final SchemaProvider schemaProvider;
    /** 反向关系需要枚举 namespace 内全部实体的 FK 声明——管理面的枚举能力在这里用。 */
    private final SchemaManager schemaManager;

    public RelatedEntityService(EntityQueryService queryService,
                                SchemaProvider schemaProvider,
                                SchemaManager schemaManager) {
        this.queryService = queryService;
        this.schemaProvider = schemaProvider;
        this.schemaManager = schemaManager;
    }

    /**
     * 导航指定行的全部（或指定字段）关系。
     *
     * @param namespace 命名空间
     * @param entity    源实体名
     * @param id        源实体主键值
     * @param field     限定关系字段；null = 返回全部正向 + 反向关系
     * @param tenant    租户上下文（多租户实体必填，沿用到目标实体查询）
     * @param agentTags Agent 的 access tags（鉴权层注入，调用方不可伪造）
     * @param page      反向关系分页页码
     * @param pageSize  反向关系分页大小
     * @return {@code {namespace, entity, id, relations: [{direction, field,
     *         entity, relatedEntity, total, page, pageSize, items}]}}
     * @throws IrisException 源实体不存在（IRIS-1001）、指定字段不是外键（IRIS-1003）
     */
    public Map<String, Object> related(
            String namespace, String entity, String id, String field,
            String tenant, List<String> agentTags, int page, int pageSize) {
        if (id == null || id.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "id 不能为空");
        }
        EntitySchema schema = schemaProvider.get(namespace, entity);

        // 1. 源行主键查询（不存在时 ENTITY_NOT_FOUND 从查询链透出）。
        // 注意 fail-closed 短路（access tags 不满足）返回的是空页而非异常，
        // 这里统一按"实体不可见/不存在"语义转成 IRIS-1001/404
        String pkField = schema.primaryKeys().get(0);
        Page<Map<String, Object>> source = queryService.query(new QueryRequest(
                namespace, entity, null, Map.of(pkField, id), 1, 1, tenant, agentTags, null, null));
        if (source.items().isEmpty()) {
            log.debug("关系导航源行不可见 ns={} entity={} id={}", namespace, entity, id);
            throw new IrisException(ErrorCode.ENTITY_NOT_FOUND,
                    "实体不存在: " + namespace + "/" + entity);
        }
        Map<String, Object> sourceRow = source.items().get(0);
        Object pkValue = sourceRow.get(pkField);

        // 2. 组装关系清单：正向 = 本实体的 FK 声明；反向 = 别的实体指向本实体
        List<FieldSchema> forward = schema.foreignKeyFields();
        List<RelationDecl> reverse = findReverseRelations(namespace, entity);
        if (field != null && !field.isBlank()) {
            forward = forward.stream().filter(f -> f.name().equals(field)).toList();
            reverse = reverse.stream().filter(r -> r.field().equals(field)).toList();
            if (forward.isEmpty() && reverse.isEmpty()) {
                throw new IrisException(ErrorCode.INVALID_QUERY,
                        "字段不是外键或无指向 " + entity + " 的外键: " + field);
            }
        }

        // 3. 逐关系执行导航查询
        List<Map<String, Object>> relations = new ArrayList<>();
        for (FieldSchema f : forward) {
            relations.add(navigateForward(namespace, schema, f, sourceRow, pkField, id,
                    tenant, agentTags));
        }
        for (RelationDecl r : reverse) {
            relations.add(navigateReverse(namespace, r, pkValue, tenant, agentTags, page, pageSize));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("namespace", namespace);
        result.put("entity", entity);
        result.put("id", id);
        result.put("relations", relations);
        log.debug("关系导航完成 ns={} entity={} id={} 关系数={}", namespace, entity, id, relations.size());
        return result;
    }

    /** 正向：取 FK 指向的目标行（多对一，单行；FK 值为空 = 无关联）。 */
    private Map<String, Object> navigateForward(
            String namespace, EntitySchema schema, FieldSchema fk,
            Map<String, Object> sourceRow, String pkField, String id,
            String tenant, List<String> agentTags) {
        EntitySchema target = schemaProvider.get(namespace, fk.relatedEntity());
        Object fkValue = sourceRow.get(fk.name());
        Page<Map<String, Object>> page = fetchTargetRow(
                namespace, fk.relatedEntity(), fkValue, tenant, agentTags);
        Map<String, Object> rel = new LinkedHashMap<>();
        rel.put("direction", "forward");
        rel.put("entity", schema.entity());
        rel.put("id", id);
        rel.put("field", fk.name());
        rel.put("relatedEntity", fk.relatedEntity());
        rel.put("targetKey", target.primaryKeys().get(0));
        rel.put("total", page.total());
        rel.put("items", page.items());
        return rel;
    }

    /**
     * 反向：查所有 fk = 本行主键值的行（一对多，分页）。
     *
     * <p>注意过滤值是<b>主键值</b>（本行在对方 FK 字段里的取值），
     * 类型由对端 Schema 决定，LONG 主键在这里以 Number 传回翻译层。
     */
    private Map<String, Object> navigateReverse(
            String namespace, RelationDecl decl, Object pkValue,
            String tenant, List<String> agentTags, int page, int pageSize) {
        EntitySchema declaring = schemaProvider.get(namespace, decl.declaringEntity());
        Page<Map<String, Object>> p;
        if (pkValue == null) {
            p = new Page<>(List.of(), 0L, page, pageSize);
        } else {
            p = queryService.query(new QueryRequest(
                    namespace, decl.declaringEntity(), null,
                    Map.of(decl.field(), pkValue), page, pageSize, tenant, agentTags, null, null));
        }
        Map<String, Object> rel = new LinkedHashMap<>();
        rel.put("direction", "reverse");
        rel.put("entity", decl.declaringEntity());
        rel.put("field", decl.field());
        rel.put("relatedEntity", declaring.entity());
        rel.put("total", p.total());
        rel.put("page", page);
        rel.put("pageSize", pageSize);
        rel.put("items", p.items());
        return rel;
    }

    /** 目标行查询：fk 值为空直接空结果；否则按目标主键等值查询（走索引）。 */
    private Page<Map<String, Object>> fetchTargetRow(
            String namespace, String targetEntity, Object fkValue,
            String tenant, List<String> agentTags) {
        if (fkValue == null || (fkValue instanceof String s && s.isBlank())) {
            return new Page<>(List.of(), 0L, 1, 1);
        }
        String targetPk = schemaProvider.get(namespace, targetEntity).primaryKeys().get(0);
        // 主键查询无结果会抛 ENTITY_NOT_FOUND：FK 指向了被删掉的行——
        // 对导航来说"关联不存在"应返回空而不是报错，这里显式降级
        try {
            return queryService.query(new QueryRequest(
                    namespace, targetEntity, null, Map.of(targetPk, fkValue),
                    1, 1, tenant, agentTags, null, null));
        } catch (IrisException e) {
            if (e.errorCode() == ErrorCode.ENTITY_NOT_FOUND) {
                log.debug("正向关系目标行不存在 ns={} entity={} {}={}",
                        namespace, targetEntity, targetPk, fkValue);
                return new Page<>(List.of(), 0L, 1, 1);
            }
            throw e;
        }
    }

    /** 枚举 namespace 内指向 {@code targetEntity} 的全部 FK 声明（反向关系）。 */
    private List<RelationDecl> findReverseRelations(String namespace, String targetEntity) {
        List<RelationDecl> result = new ArrayList<>();
        for (EntitySchema s : schemaManager.list()) {
            if (!s.namespace().equals(namespace)) {
                continue;
            }
            for (FieldSchema f : s.foreignKeyFields()) {
                if (f.relatedEntity().equals(targetEntity)) {
                    result.add(new RelationDecl(s.entity(), f.name(), targetEntity));
                }
            }
        }
        return result;
    }

    /** 反向关系声明：哪个实体（declaringEntity）的哪个字段（field）指向本实体。 */
    private record RelationDecl(String declaringEntity, String field, String relatedEntity) {
    }
}

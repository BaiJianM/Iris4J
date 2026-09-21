package com.iris.lite.api.controller;

import com.iris.lite.api.security.AgentIdentity;
import com.iris.lite.application.ops.RedisInsightService;
import com.iris.lite.application.schema.SchemaAdminService;
import com.iris.lite.context.schema.EntitySchema;
import com.iris.lite.context.schema.SchemaManager;
import com.iris.lite.context.schema.SchemaStatus;
import com.iris.lite.shared.error.ErrorCode;
import com.iris.lite.shared.error.IrisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST Schema 管理入口（动态 Schema）。
 *
 * <p>排查"改了 YAML 没生效"的标准动作：先看 GET /status 确认
 * hotReloadEnabled 与 schemaDir，再看 lastError 是否非空。
 *
 * <p>实体清单/详情（{@code /entities}、{@code /{ns}/{entity}}）
 * 与 FT 索引详情（{@code /{ns}/{entity}/index}），供管理控制台
 * Schema 管理页展示。均只读。
 *
 * <p>关系映射编辑 {@code PUT /{ns}/{entity}/fields/{field}/related-entity}——
 * 控制台下拉选择 Related Entity 后回写 YAML 并热载。属管理类操作，
 * 与 agent key 管理同款 operator 校验（legacy key / 鉴权关闭放行）。
 */
@RestController
@RequestMapping("/api/v1/schema")
public class SchemaController {

    private static final Logger log = LoggerFactory.getLogger(SchemaController.class);

    private final SchemaAdminService adminService;
    private final SchemaManager schemaManager;
    private final RedisInsightService insightService;

    public SchemaController(SchemaAdminService adminService,
                            SchemaManager schemaManager,
                            RedisInsightService insightService) {
        this.adminService = adminService;
        this.schemaManager = schemaManager;
        this.insightService = insightService;
    }

    /** 查询热加载状态。 */
    @GetMapping("/status")
    public SchemaStatus status() {
        return adminService.status();
    }

    /**
     * 强制重载外部 Schema，返回重载后的最新状态。
     *
     * <p>返回状态而非空响应：调用方能立刻看到是否成功（lastError 是否清空、
     * schemaCount 是否变化），不必再调一次 status。
     */
    @PostMapping("/reload")
    public SchemaStatus reload() {
        log.info("Schema 重载请求（REST）");
        adminService.reload();
        return adminService.status();
    }

    /**
     * 实体清单：当前生效的全部 Schema 的摘要视图。
     *
     * <p>namespace 可选过滤；缺省返回全部 namespace 的实体。
     * 字段统计在 Java 层现算（索引字段数/关系字段数）——
     * Schema record 直接序列化会带上完整字段数组，清单页只需要摘要。
     */
    @GetMapping("/entities")
    public List<Map<String, Object>> entities(@RequestParam(required = false) String namespace) {
        return schemaManager.list().stream()
                .filter(s -> namespace == null || namespace.isBlank()
                        || s.namespace().equals(namespace))
                .map(this::toSummary)
                .toList();
    }

    /** 实体 Schema 详情：完整字段定义（含 index/tags/relatedEntity/description）。 */
    @GetMapping("/{namespace}/{entity}")
    public EntitySchema detail(@PathVariable String namespace, @PathVariable String entity) {
        return schemaManager.get(namespace, entity);
    }

    /** 实体 FT 索引详情：索引名 + num_docs + 字段类型映射。 */
    @GetMapping("/{namespace}/{entity}/index")
    public Map<String, Object> index(@PathVariable String namespace, @PathVariable String entity) {
        return insightService.indexInfo(namespace, entity);
    }

    /** 关系映射编辑请求体：{@code relatedEntity} 传 null 表示清除映射。 */
    public record RelatedEntityRequest(String relatedEntity) {
    }

    /**
     * 更新字段的关系映射：回写 YAML 后热载，返回更新结果与最新 Schema 详情。
     *
     * <p>响应体 {@code indexAutoAssigned=true} 表示因 FK 强制索引自动补了显式索引
     * （{@code assignedIndex} 为补的形态），前端据此提示用户。
     */
    @PutMapping("/{namespace}/{entity}/fields/{fieldName}/related-entity")
    public Map<String, Object> updateRelatedEntity(@PathVariable String namespace,
                                                   @PathVariable String entity,
                                                   @PathVariable String fieldName,
                                                   @RequestBody RelatedEntityRequest request) {
        requireOperator("updateRelatedEntity");
        SchemaAdminService.RelatedEntityUpdateResult result =
                adminService.updateRelatedEntity(namespace, entity, fieldName,
                        request == null ? null : request.relatedEntity());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("namespace", result.namespace());
        body.put("entity", result.entity());
        body.put("field", result.field());
        body.put("relatedEntity", result.relatedEntity());
        body.put("indexAutoAssigned", result.indexAutoAssigned());
        body.put("assignedIndex", result.assignedIndex());
        body.put("status", adminService.status());
        body.put("schema", schemaManager.get(namespace, entity));
        return body;
    }

    /**
     * operator 校验：与 agent key 管理同款——Schema 编辑是管理类操作，
     * per-agent 动态 key 不允许改模型；无身份（鉴权关闭，本地开发）放行。
     */
    private void requireOperator(String action) {
        var attrs = RequestContextHolder.getRequestAttributes();
        AgentIdentity identity = attrs instanceof ServletRequestAttributes sra
                ? (AgentIdentity) sra.getRequest().getAttribute(AgentIdentity.REQUEST_ATTRIBUTE)
                : null;
        if (identity == null) {
            return;
        }
        if (!identity.hasTag(AgentIdentity.WILDCARD_TAG)) {
            throw new IrisException(ErrorCode.UNAUTHORIZED,
                    "Schema 编辑 (" + action + ") 需要 operator 身份（legacy key）");
        }
    }

    private Map<String, Object> toSummary(EntitySchema s) {
        return Map.of(
                "namespace", s.namespace(),
                "entity", s.entity(),
                "primaryKeys", s.primaryKeys(),
                "fieldCount", s.fields().size(),
                "indexedFields", s.fields().stream().filter(f -> f.indexed() || f.index() != null).count(),
                "relatedFields", s.fields().stream().filter(f -> f.relatedEntity() != null).count(),
                "tenantField", s.tenantField() == null ? "" : s.tenantField(),
                "accessTagField", s.accessTagField() == null ? "" : s.accessTagField());
    }
}


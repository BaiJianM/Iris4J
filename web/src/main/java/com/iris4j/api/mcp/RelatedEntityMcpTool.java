package com.iris4j.api.mcp;

import com.iris4j.api.security.AgentIdentity;
import com.iris4j.application.query.RelatedEntityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * MCP 跨实体关系导航工具（Related Entity）。
 *
 * <p>与 REST 共用 {@link RelatedEntityService}；agentTags 同样从
 * request attribute 取（McpRequestIdentity），行级/字段级裁剪自动生效。
 */
@Component
public class RelatedEntityMcpTool {

    private static final Logger log = LoggerFactory.getLogger(RelatedEntityMcpTool.class);

    private final RelatedEntityService relatedEntityService;

    public RelatedEntityMcpTool(RelatedEntityService relatedEntityService) {
        this.relatedEntityService = relatedEntityService;
    }

    /**
     * 导航一个实体的关系（正向 + 反向）。
     *
     * <p>返回结构化 Map：relations 数组每项含 direction（forward=该行指向的实体，
     * reverse=指向该行的实体）、field、entity、total、items。
     */
    @McpTool(name = "get_related_entities",
            description = "跨实体关系导航：给定实体主键值，返回其全部关联数据。"
                    + "forward=本行外键指向的实体行（多对一），"
                    + "reverse=其他实体中外键指向本行的行（一对多）。"
                    + "关系由 Schema 的 relatedEntity 声明推导，查询走索引并自动套用访问治理。")
    public Map<String, Object> getRelatedEntities(
            @McpToolParam(description = "命名空间（可省略时用服务端默认配置）", required = true) String namespace,
            @McpToolParam(description = "实体名（须为 Schema 中已注册的实体）", required = true) String entity,
            @McpToolParam(description = "主键值，如 1001", required = true) String id,
            @McpToolParam(description = "限定关系字段名（可空 = 全部关系）", required = false) String field,
            @McpToolParam(description = "租户（多租户实体必填，如 t1）", required = false) String tenant,
            @McpToolParam(description = "反向关系页码，默认 1", required = false) Integer page,
            @McpToolParam(description = "反向关系每页大小，默认 20", required = false) Integer pageSize) {
        AgentIdentity identity = McpRequestIdentity.resolve();
        List<String> agentTags = identity == null ? null : identity.tags();
        log.debug("MCP 关系导航 ns={} entity={} id={} field={}", namespace, entity, id, field);
        return relatedEntityService.related(namespace, entity, id, field, tenant, agentTags,
                page == null ? 1 : page, pageSize == null ? 20 : pageSize);
    }
}

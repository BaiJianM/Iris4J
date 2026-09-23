package com.iris4j.api.controller;

import com.iris4j.api.mcp.McpRequestIdentity;
import com.iris4j.api.security.AgentIdentity;
import com.iris4j.application.query.RelatedEntityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 跨实体关系导航入口（Related Entity）。
 *
 * <p>GET 一个实体的某行，返回它的全部关系（正向 + 反向）；
 * {@code field} 参数可限定单个关系。
 *
 * <p><b>agentTags 注入</b>：tags 来自鉴权过滤器写入的 request attribute，
 * 不从参数取——调用方伪造不了自己的权限标签。关系查询复用整条治理链，
 * 未授权的行/字段在导航结果里同样被裁剪。
 */
@RestController
@RequestMapping("/api/v1/entities")
public class RelatedEntityController {

    private final RelatedEntityService relatedEntityService;

    public RelatedEntityController(RelatedEntityService relatedEntityService) {
        this.relatedEntityService = relatedEntityService;
    }

    @GetMapping("/{entity}/{id}/related")
    public Map<String, Object> related(
            @PathVariable String entity,
            @PathVariable String id,
            @RequestParam String namespace,
            @RequestParam(required = false) String field,
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        List<String> agentTags = currentAgentTags();
        return relatedEntityService.related(namespace, entity, id, field, tenant, agentTags,
                page == null ? 1 : page, pageSize == null ? 20 : pageSize);
    }

    /** 从鉴权过滤器写入的 request attribute 取身份；鉴权关闭时为 null（无裁剪）。 */
    private List<String> currentAgentTags() {
        AgentIdentity identity = McpRequestIdentity.resolve();
        return identity == null ? null : identity.tags();
    }
}

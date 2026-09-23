package com.iris.lite.java.api.controller;

import com.iris.lite.java.api.dto.QueryRequestDto;
import com.iris.lite.java.api.security.AgentIdentity;
import com.iris.lite.java.application.query.EntityQueryService;
import com.iris.lite.java.context.model.QueryRequest;
import com.iris.lite.java.context.model.RangeFilter;
import com.iris.lite.java.shared.model.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST 查询入口。
 *
 * <p><b>为什么用 POST 而不是 GET</b>：filters 是任意结构的 Map，
 * 用 query string 表达嵌套结构既别扭又有长度限制。
 * 查询语义上是"读"，但传参复杂度决定了 POST + JSON body 更合适。
 */
@RestController
@RequestMapping("/api/v1/entities")
public class EntityQueryController {

    private static final Logger log = LoggerFactory.getLogger(EntityQueryController.class);

    private final EntityQueryService queryService;

    public EntityQueryController(EntityQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 实体查询：{@code POST /api/v1/entities/{entity}/query}。
     *
     * <p>entity 走路径参数、其余走请求体——entity 是资源定位信息，
     * 放在路径上更符合 REST 语义，也便于网关按路径做路由与限流。
     *
     * <p><b>agentTags 注入</b>：tags 来自鉴权过滤器解析的 AgentIdentity
     * （request attribute），不从 DTO 取——调用方无法伪造自己的权限标签。
     * DTO 不暴露 agentTags 字段是刻意的。
     */
    @PostMapping("/{entity}/query")
    public Page<Map<String, Object>> query(
            @PathVariable String entity,
            @RequestBody QueryRequestDto dto,
            HttpServletRequest httpRequest) {
        // DTO -> 领域模型：DTO 的 page/pageSize 是装箱类型（缺省为 null），
        // QueryRequest 的紧凑构造器会做归一化；范围条件 DTO -> 领域 record 逐个转换
        Object attr = httpRequest.getAttribute(AgentIdentity.REQUEST_ATTRIBUTE);
        AgentIdentity agent = attr instanceof
                AgentIdentity a ? a : null;
        Map<String, RangeFilter> rangeFilters = null;
        if (dto.rangeFilters() != null) {
            Map<String, RangeFilter> converted = new LinkedHashMap<>();
            dto.rangeFilters().forEach((k, v) -> converted.put(k,
                    RangeFilter.parse(v.min(), v.max())));
            rangeFilters = converted;
        }
        QueryRequest request = new QueryRequest(
                dto.namespace(), entity, dto.fields(), dto.filters(),
                dto.page(), dto.pageSize(), dto.tenant(),
                agent == null ? null : agent.tags(),
                rangeFilters, dto.textFilters(),
                dto.sortField(), dto.sortDesc() != null && dto.sortDesc());
        log.debug("REST 查询请求 ns={} entity={} filters={} page={} pageSize={} tenant={} agent={}",
                request.namespace(), entity, request.filters(),
                request.page(), request.pageSize(), request.tenant(),
                agent == null ? "-" : agent.agentId());
        return queryService.query(request);
    }
}

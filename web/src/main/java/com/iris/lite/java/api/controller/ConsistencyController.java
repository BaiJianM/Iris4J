package com.iris.lite.java.api.controller;

import com.iris.lite.java.application.ops.ConsistencyCheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理面端点：源库-投影一致性校验与修复。
 *
 * <p>挂 {@code /api/v1/admin} 前缀：与业务查询入口分离，便于网关按路径
 * 收紧访问（只允许运维来源调用）；鉴权仍走统一的 API-Key 过滤器。
 *
 * <p>触发方式是 POST 而非 GET：校验（尤其 full 档 + 修复）有实际成本
 * （拉源表全量、写投影），应显式触发而非被爬虫/监控误触。
 */
@RestController
@RequestMapping("/api/v1/admin/consistency")
public class ConsistencyController {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyController.class);

    private final ConsistencyCheckService service;

    public ConsistencyController(ConsistencyCheckService service) {
        this.service = service;
    }

    /**
     * 校验：{@code POST /api/v1/admin/consistency/check}。
     * body: {"namespace":"...","entity":"...","mode":"count|full","tableName":"..."(可选)}
     */
    @PostMapping("/check")
    public ConsistencyCheckService.ConsistencyReport check(
            @RequestBody ConsistencyCheckService.CheckRequest request) {
        log.info("一致性校验触发 ns={} entity={} mode={}", request.namespace(), request.entity(), request.mode());
        return service.check(request);
    }

    /**
     * 修复：{@code POST /api/v1/admin/consistency/repair}（以源库为准）。
     * body 同 check。
     */
    @PostMapping("/repair")
    public ConsistencyCheckService.RepairResult repair(
            @RequestBody ConsistencyCheckService.CheckRequest request) {
        log.info("一致性修复触发 ns={} entity={}", request.namespace(), request.entity());
        return service.repair(request);
    }
}

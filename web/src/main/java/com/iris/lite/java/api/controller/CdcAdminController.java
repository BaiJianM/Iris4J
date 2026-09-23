package com.iris.lite.java.api.controller;

import com.iris.lite.java.application.ops.CdcInsightService;
import com.iris.lite.java.application.ops.DlqAdminService;
import com.iris.lite.java.application.ops.DlqEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST CDC/DLQ 管理入口（Stream 重试、DLQ 和人工重放）。
 *
 * <p>毒消息处理流程：GET /dlq 看条目（含 error 原因）-> 修复根因
 * -> POST /dlq/replay 重放 -> 再 GET /dlq 确认清空。
 *
 * <p>管道观测（{@code /sources}、{@code /sources/{index}/pel}），
 * 供管理控制台展示积压/待处理消息，只读。
 */
@RestController
@RequestMapping("/api/v1/cdc")
public class CdcAdminController {

    private static final Logger log = LoggerFactory.getLogger(CdcAdminController.class);

    private final DlqAdminService dlqAdminService;
    private final CdcInsightService cdcInsightService;

    public CdcAdminController(DlqAdminService dlqAdminService, CdcInsightService cdcInsightService) {
        this.dlqAdminService = dlqAdminService;
        this.cdcInsightService = cdcInsightService;
    }

    /** 查看 DLQ 条目；namespace/entity 可选过滤，limit 默认 50。 */
    @GetMapping("/dlq")
    public List<DlqEntry> list(
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String entity,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        List<DlqEntry> entries = dlqAdminService.list(namespace, entity, limit);
        log.debug("DLQ 查询请求 ns={} entity={} limit={} 返回={}",
                namespace, entity, limit, entries.size());
        return entries;
    }

    /**
     * 人工重放 DLQ：走正常投影路径，成功条目从 DLQ 移除，失败条目保留。
     *
     * <p>返回重放成功的条数。若返回 0 而 DLQ 仍有条目，
     * 说明根因未修复——去看条目的 error 字段。
     */
    @PostMapping("/dlq/replay")
    public Map<String, Object> replay(
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String entity) {
        long replayed = dlqAdminService.replay(namespace, entity);
        log.info("DLQ 重放请求（REST）ns={} entity={} 成功={}", namespace, entity, replayed);
        return Map.of("replayed", replayed);
    }

    /**
     * 管道 source 列表：每个 source 的 stream 积压（XLEN）、
     * 待处理（XPENDING 摘要）、DLQ 积压计数。计数取不到（stream 未创建）为 null。
     */
    @GetMapping("/sources")
    public List<CdcInsightService.SourceView> sources() {
        return cdcInsightService.sources();
    }

    /** 指定 source 的 PEL 明细：已投递未确认消息，按投递次数/空闲时长排查毒消息。 */
    @GetMapping("/sources/{index}/pel")
    public List<CdcInsightService.PendingView> pending(
            @PathVariable int index,
            @RequestParam(required = false, defaultValue = "50") int limit) {
        List<CdcInsightService.PendingView> pending = cdcInsightService.pending(index, limit);
        log.debug("PEL 查询请求 index={} limit={} 返回={}", index, limit, pending.size());
        return pending;
    }
}

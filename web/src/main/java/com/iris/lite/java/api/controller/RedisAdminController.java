package com.iris.lite.java.api.controller;

import com.iris.lite.java.api.security.AgentIdentity;
import com.iris.lite.java.application.ops.RedisInsightService;
import com.iris.lite.java.shared.error.ErrorCode;
import com.iris.lite.java.shared.error.IrisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

/**
 * 管理面 Redis 观测端点（管理控制台，只读）。
 *
 * <p>挂 {@code /api/v1/admin} 前缀（operator 专属）：
 * 键空间扫描暴露全部数据（绕过实体治理链），只应给有 operator 权限的人看。
 * 控制台键浏览页与 Redis 统计卡走这里。
 *
 * <p><b>只读红线</b>：SCAN/TYPE/PTTL/INFO/FT.INFO 纯观测，
 * 不提供键编辑——写路径绕过治理链且制造投影漂移。
 */
@RestController
@RequestMapping("/api/v1/admin/redis")
public class RedisAdminController {

    private static final Logger log = LoggerFactory.getLogger(RedisAdminController.class);

    private final RedisInsightService insightService;

    public RedisAdminController(RedisInsightService insightService) {
        this.insightService = insightService;
    }

    /**
     * 有界键扫描。pattern 走查询参数。
     *
     * <p><b>不做手动 URLDecoder</b>：Spring 已对 @RequestParam 完成一次百分号解码，
     * 再解一次会把含 {@code +}/{@code %} 的键名静默破坏（+→空格、裸 % 抛 400）。
     * 客户端 encodeURIComponent 后 Spring 解一次即得原始值。
     *
     * <p>pattern 为空时默认 {@code *}（整库前 200 条）；limit 1-500。
     */
    @GetMapping("/keys")
    public Map<String, Object> keys(
            @RequestParam(required = false, defaultValue = "*") String pattern,
            @RequestParam(required = false, defaultValue = "200") int limit) {
        requireOperator("keys");
        RedisInsightService.KeyPage page = insightService.scanKeys(pattern, limit);
        log.debug("键浏览请求 pattern={} limit={} 返回={} truncated={}",
                pattern, limit, page.keys().size(), page.truncated());
        return Map.of(
                "pattern", pattern,
                "keys", page.keys(),
                "count", page.keys().size(),
                "truncated", page.truncated());
    }

    /** 单键只读视图（TYPE + 按类型负载 + PTTL）。路径变量经 URL 编码可含冒号，Spring 已解码一次。 */
    @GetMapping("/keys/{key}")
    public RedisInsightService.KeyValueView viewKey(@PathVariable String key) {
        requireOperator("viewKey");
        return insightService.viewKey(key);
    }

    /** 连接与吞吐统计（INFO clients + stats 投影 + 现算命中率）。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        requireOperator("stats");
        return insightService.stats();
    }

    /**
     * operator 校验：与 AgentKeyAdminController 同策略——
     * 无身份（鉴权关闭）通过；有身份必须持通配 {@code *}（legacy key）。
     */
    private void requireOperator(String action) {
        var attrs = RequestContextHolder.getRequestAttributes();
        AgentIdentity identity = attrs instanceof ServletRequestAttributes sra
                ? (AgentIdentity) sra.getRequest().getAttribute(AgentIdentity.REQUEST_ATTRIBUTE)
                : null;
        if (identity == null) {
            return; // 鉴权关闭（本地开发默认）
        }
        if (!identity.hasTag(AgentIdentity.WILDCARD_TAG)) {
            throw new IrisException(ErrorCode.UNAUTHORIZED,
                    "Redis 观测 (" + action + ") 需要 operator 身份（legacy key）");
        }
    }
}

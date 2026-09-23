package com.iris4j.api.controller;

import com.iris4j.api.security.AgentIdentity;
import com.iris4j.api.security.AgentKeyRegistry;
import com.iris4j.application.agent.AgentKeyAdminService;
import com.iris4j.application.agent.AgentKeyEntry;
import com.iris4j.application.agent.AgentKeyStore;
import com.iris4j.shared.error.ErrorCode;
import com.iris4j.shared.error.IrisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理面端点：动态 agent key 管理（免重启热载）。
 *
 * <p>挂 {@code /api/v1/admin} 前缀（便于网关按路径收紧来源）；
 * 鉴权仍走统一的 API-Key 过滤器，<b>本控制器额外要求 operator 身份</b>：
 * 请求身份须持通配 tag {@code *}（即 legacy key）——"有 operator 权限的人才能发 key"。
 * 鉴权整体关闭（本地开发）时通过，与全局姿态一致。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET  /api/v1/admin/agent-keys}——列表（静态 + 动态，无明文 key）；</li>
 *   <li>{@code PUT  /api/v1/admin/agent-keys}——新增/更新（body: agentId/key/tags），
 *       写后即时刷新注册表，<b>下一笔请求即按新身份生效</b>；</li>
 *   <li>{@code DELETE /api/v1/admin/agent-keys/{fingerprint}}——按指纹删除动态条目
 *       （URL/日志只出现指纹，不落明文 key）。</li>
 * </ul>
 *
 * <p><b>轮换流程（零停机）</b>：PUT 新 key（同一 agentId）→ 调用方切到新 key
 * → DELETE 旧 key 指纹。新旧并存期间两把都有效。
 */
@RestController
@RequestMapping("/api/v1/admin/agent-keys")
public class AgentKeyAdminController {

    private static final Logger log = LoggerFactory.getLogger(AgentKeyAdminController.class);

    private final AgentKeyAdminService service;
    private final AgentKeyRegistry registry;

    public AgentKeyAdminController(AgentKeyAdminService service, AgentKeyRegistry registry) {
        this.service = service;
        this.registry = registry;
    }

    /** 动态 key 新增/更新请求体。 */
    public record UpsertRequest(String agentId, String key, List<String> tags) {
    }

    /**
     * 列表：静态基线 + 动态条目（均不含明文 key）。
     */
    @GetMapping
    public Map<String, Object> list() {
        requireOperator("list");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("staticCount", registry.staticCount());
        result.put("static", registry.staticViews());
        result.put("dynamicCount", registry.dynamicCount());
        result.put("dynamic", service.list());
        return result;
    }

    /**
     * 新增/更新动态 key。同一把 key（同指纹）重复 PUT = 覆盖 tags（热更新路径）；
     * 覆盖静态基线里已有 key 时响应带 {@code overrideStatic=true} 提示。
     */
    @PutMapping
    public Map<String, Object> upsert(@RequestBody UpsertRequest request) {
        requireOperator("upsert");
        AgentKeyAdminService.UpsertResult result =
                service.upsert(new AgentKeyAdminService.UpsertCommand(
                        request.agentId(), request.key(), request.tags()));
        // 写后即时刷新：不等 30s 对账，下一笔请求即按新 tags 裁剪
        registry.refresh();
        boolean overrideStatic = registry.isStaticFingerprint(result.fingerprint());
        if (overrideStatic) {
            log.warn("动态 key 覆盖了静态基线中的同一把 key agentId={}", result.agentId());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fingerprint", result.fingerprint());
        body.put("shortFingerprint", result.shortFingerprint());
        body.put("agentId", result.agentId());
        body.put("tags", result.tags());
        body.put("updatedAt", result.updatedAt());
        body.put("overrideStatic", overrideStatic);
        return body;
    }

    /**
     * 删除动态 key（按指纹）。静态基线的 key 删不到（动态层无此指纹 → 404）。
     */
    @DeleteMapping("/{fingerprint}")
    public Map<String, Object> delete(@PathVariable String fingerprint) {
        requireOperator("delete");
        AgentKeyEntry removed = service.delete(fingerprint);
        if (removed == null) {
            throw new IrisException(ErrorCode.ENTITY_NOT_FOUND,
                    "动态 agent key 不存在（静态配置的 key 请改 yml 后重启）");
        }
        registry.refresh();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", true);
        body.put("agentId", removed.agentId());
        body.put("shortFingerprint", removed.shortFingerprint());
        return body;
    }

    /**
     * operator 校验：无身份（鉴权关闭）通过；有身份必须持通配 {@code *}（legacy）。
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
                    "agent key 管理 (" + action + ") 需要 operator 身份（legacy key）");
        }
    }
}

package com.iris4j.api.security;

import com.iris4j.application.agent.AgentKeyAdminService;
import com.iris4j.application.agent.AgentKeyEntry;
import com.iris4j.application.agent.AgentKeyStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent key 注册表：静态 yml 基线 + 动态 Redis 条目的合并快照，定时热载。
 *
 * <p><b>解决的问题</b>：per-agent key 若在过滤器构造器里一次性构建，
 * 增删 key / 改 tags 必须重启。本类把"key → 身份"解析改为查可刷新快照：
 * 动态条目存 Redis
 * （经 {@link AgentKeyAdminService} 读写），定时对账 + 管理写后即时刷新，
 * 免重启生效。
 *
 * <p><b>合并语义</b>：解析优先级 <b>动态 &gt; 静态 &gt; legacy</b>——
 * 同一把 key 静态与动态都配置时动态条目胜出（运行时改 tags 的覆盖路径）；
 * 静态条目始终在册（yml 是基线，动态层删不掉它）。
 *
 * <p><b>动态条目按指纹解析</b>：动态存储只有 key 的 SHA-256 指纹（无明文），
 * 解析时对请求头 key 现算指纹再查表——Map 精确命中，不泄露任何比对时序信息
 * （攻击者拿不到指纹的逆像）；静态与 legacy 仍用
 * {@link MessageDigest#isEqual} 常量时间逐把比对。
 *
 * <p><b>刷新失败降级</b>：Redis 抖动导致 loadAll 失败时保留上一次快照继续服务
 * （鉴权可用性优先），下个周期自动重试——绝不让快照变 null。
 */
@Component
public class AgentKeyRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentKeyRegistry.class);

    /** 静态基线：明文 key -> 身份（yml agents[]，启动构建后不变）。 */
    private final Map<String, AgentIdentity> staticKeys;
    /** legacy 兼容 key（空串 = 未启用）。 */
    private final String legacyKey;
    private final AgentKeyAdminService adminService;

    /** 动态条目快照：指纹 -> 身份。volatile 保证读线程（请求线程）可见性。 */
    private volatile Map<String, AgentIdentity> dynamicByFingerprint = Map.of();

    public AgentKeyRegistry(SecurityProperties props, AgentKeyAdminService adminService) {
        Map<String, AgentIdentity> map = new HashMap<>();
        for (SecurityProperties.AgentKeyDef def : props.agents()) {
            // key 为空的条目忽略（yml 占位符未注入环境变量的常见形态）
            if (def.key() == null || def.key().isBlank()) {
                continue;
            }
            map.put(def.key().trim(), new AgentIdentity(def.agentId(), def.tags()));
        }
        this.staticKeys = Map.copyOf(map);
        this.legacyKey = props.apiKey() == null ? "" : props.apiKey().trim();
        this.adminService = adminService;
    }

    /** 启动即拉一次动态条目（失败不阻断启动：静态基线照常服务）。 */
    @PostConstruct
    public void refreshAtStartup() {
        refresh();
    }

    /**
     * 周期对账：默认 30s（{@code iris.security.agent-keys.refresh-ms}）。
     * 管理接口写操作后会额外即时刷新，本定时器兜底"绕过管理接口直接改 Redis"的场景。
     */
    @Scheduled(fixedDelayString = "${iris.security.agent-keys.refresh-ms:30000}")
    public void refresh() {
        try {
            Map<String, AgentIdentity> snapshot = new HashMap<>();
            for (AgentKeyEntry entry : adminService.list()) {
                snapshot.put(entry.fingerprint(), new AgentIdentity(entry.agentId(), entry.tags()));
            }
            this.dynamicByFingerprint = Map.copyOf(snapshot);
            log.debug("动态 agent key 快照已刷新，条目数={}", snapshot.size());
        } catch (Exception e) {
            // 保留旧快照继续服务（鉴权可用性优先），等下个周期重试。
            // 带完整堆栈：NPE 的 getMessage() 是 null，只打 message 会变成无头日志
            log.warn("动态 agent key 刷新失败，沿用上一次快照", e);
        }
    }

    /**
     * 解析 API-Key -> 身份。未命中返回 null（由过滤器统一写 401）。
     *
     * <p>优先级：动态（指纹精确匹配）&gt; 静态 &gt; legacy。
     */
    public AgentIdentity identityFor(String provided) {
        if (provided == null) {
            return null;
        }
        // 1. 动态条目：指纹精确匹配（指纹本身不可逆，无需常量时间比对）
        AgentIdentity dynamic = dynamicByFingerprint.get(AgentKeyStore.fingerprint(provided));
        if (dynamic != null) {
            return dynamic;
        }
        // 2. 静态基线：明文常量时间比对（防时序侧信道）
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        for (Map.Entry<String, AgentIdentity> e : staticKeys.entrySet()) {
            if (MessageDigest.isEqual(providedBytes, e.getKey().getBytes(StandardCharsets.UTF_8))) {
                return e.getValue();
            }
        }
        // 3. legacy 兼容身份
        if (!legacyKey.isEmpty()
                && MessageDigest.isEqual(providedBytes, legacyKey.getBytes(StandardCharsets.UTF_8))) {
            return AgentIdentity.legacy();
        }
        return null;
    }

    /** 鉴权是否开启：静态、动态、legacy 全空 = 关闭（本地开发默认）。 */
    public boolean isAuthEnabled() {
        return !legacyKey.isEmpty() || !staticKeys.isEmpty() || !dynamicByFingerprint.isEmpty();
    }

    /** 静态基线条目数（诊断展示用）。 */
    public int staticCount() {
        return staticKeys.size();
    }

    /** 动态条目数（诊断展示用）。 */
    public int dynamicCount() {
        return dynamicByFingerprint.size();
    }

    /**
     * 指纹是否命中静态基线（管理接口的"静态覆盖"提示用）。
     */
    public boolean isStaticFingerprint(String fingerprint) {
        for (String key : staticKeys.keySet()) {
            if (AgentKeyStore.fingerprint(key).equals(fingerprint)) {
                return true;
            }
        }
        return false;
    }

    /** 静态条目明细（agentId + tags，管理列表诊断展示用；不泄露明文 key）。 */
    public List<StaticAgentKeyView> staticViews() {
        return staticKeys.entrySet().stream()
                .map(e -> new StaticAgentKeyView(e.getValue().agentId(), e.getValue().tags()))
                .toList();
    }

    /** 静态条目视图（无明文 key）。 */
    public record StaticAgentKeyView(String agentId, List<String> tags) {
    }
}

package com.iris.lite.application.agent;

import com.iris.lite.shared.error.ErrorCode;
import com.iris.lite.shared.error.IrisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动态 agent key 管理服务：运行时增删 per-agent key，免重启热载。
 *
 * <p><b>与静态配置的关系</b>：yml {@code iris.security.agents[]} 仍是基线
 * （进程启动即生效、不可经本服务删除）；动态条目存 Redis（{@link AgentKeyStore}），
 * 注册表合并两方（同指纹动态覆盖静态——用于不改部署改某把 key 的 tags）。
 *
 * <p><b>安全约束</b>：
 * <ul>
 *   <li>tags 严禁包含通配 {@code *}——那是 legacy 兼容身份的保留值，
 *       动态 key 若可自授通配等于把"全可见"开放给管理接口调用方，越权底线；</li>
 *   <li>管理操作（谁有资格调）由 api 层控制器校验：须持 legacy 通配身份，
 *       即"有 operator 权限的人才能发 key"，本层只管条目本身的合法性；</li>
 *   <li>明文 key 只在本层做过指纹转换，不进日志、不进响应体。</li>
 * </ul>
 */
@Service
public class AgentKeyAdminService {

    private static final Logger log = LoggerFactory.getLogger(AgentKeyAdminService.class);

    /** key 最短长度：低于此值视为弱凭证直接拒绝（8 位以下纯猜测成本过低）。 */
    private static final int MIN_KEY_LENGTH = 8;
    private static final int MAX_KEY_LENGTH = 256;
    private static final int MAX_AGENT_ID_LENGTH = 128;
    private static final int MAX_TAGS = 32;
    private static final int MAX_TAG_LENGTH = 64;

    private final AgentKeyStore store;

    public AgentKeyAdminService(AgentKeyStore store) {
        this.store = store;
    }

    /** 新增/更新请求：明文 key 只在这里出现一次（转指纹后即弃）。 */
    public record UpsertCommand(String agentId, String key, List<String> tags) {
    }

    /** 新增/更新结果：指纹 + 短指纹（供后续 DELETE /{fingerprint} 与日志展示）。 */
    public record UpsertResult(String fingerprint, String shortFingerprint,
                               String agentId, List<String> tags, String updatedAt) {
    }

    /**
     * 新增或更新一把动态 agent key。校验失败抛 {@link IrisException}（400）。
     */
    public UpsertResult upsert(UpsertCommand command) {
        if (command == null || command.agentId() == null || command.agentId().isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "agentId 不能为空");
        }
        String agentId = command.agentId().trim();
        if (agentId.length() > MAX_AGENT_ID_LENGTH) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "agentId 过长（上限 " + MAX_AGENT_ID_LENGTH + "）");
        }
        if (command.key() == null || command.key().isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "key 不能为空");
        }
        String key = command.key().trim();
        if (key.length() < MIN_KEY_LENGTH) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "key 过短（至少 " + MIN_KEY_LENGTH + " 位，防弱凭证）");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "key 过长（上限 " + MAX_KEY_LENGTH + "）");
        }
        List<String> tags = normalizeTags(command.tags());

        String fingerprint = AgentKeyStore.fingerprint(key);
        String updatedAt = Instant.now().toString();
        store.save(fingerprint, agentId, tags);
        log.info("动态 agent key 已保存 agentId={} fp={} tags={}", agentId, fp8(fingerprint), tags);
        return new UpsertResult(fingerprint, fp8(fingerprint), agentId, tags, updatedAt);
    }

    /**
     * 删除一把动态 agent key（按指纹定位）。
     *
     * @return 被删条目；指纹不存在返回 null（含"那是静态配置的 key，动态层管不到"的情况）
     */
    public AgentKeyEntry delete(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "fingerprint 不能为空");
        }
        Map<String, AgentKeyEntry> all = store.loadAll();
        AgentKeyEntry entry = all.get(fingerprint);
        if (entry == null) {
            return null;
        }
        store.delete(fingerprint);
        log.info("动态 agent key 已删除 agentId={} fp={}", entry.agentId(), fp8(fingerprint));
        return entry;
    }

    /** 全量动态条目（无明文 key）。 */
    public List<AgentKeyEntry> list() {
        return List.copyOf(store.loadAll().values());
    }

    /**
     * tags 归一化 + 校验：去重、禁空、禁通配 {@code *}、限量限长。
     */
    private List<String> normalizeTags(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > MAX_TAGS) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "tags 数量过多（上限 " + MAX_TAGS + "）");
        }
        Set<String> deduped = new LinkedHashSet<>();
        for (String tag : raw) {
            if (tag == null || tag.isBlank()) {
                throw new IrisException(ErrorCode.INVALID_QUERY, "tags 含空白项");
            }
            String t = tag.trim();
            if ("*".equals(t)) {
                // legacy 通配是保留身份，动态 key 自授 * = 管理接口越权全可见
                throw new IrisException(ErrorCode.INVALID_QUERY, "tags 不允许包含通配 *（legacy 保留身份）");
            }
            if (t.length() > MAX_TAG_LENGTH) {
                throw new IrisException(ErrorCode.INVALID_QUERY, "tag 过长（上限 " + MAX_TAG_LENGTH + "）");
            }
            deduped.add(t);
        }
        return new ArrayList<>(deduped);
    }

    private static String fp8(String fingerprint) {
        return fingerprint.length() <= 8 ? fingerprint : fingerprint.substring(0, 8);
    }
}

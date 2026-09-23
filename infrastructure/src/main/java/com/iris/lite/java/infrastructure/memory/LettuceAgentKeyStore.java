package com.iris.lite.java.infrastructure.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.lite.java.application.agent.AgentKeyEntry;
import com.iris.lite.java.application.agent.AgentKeyStore;
import com.iris.lite.java.infrastructure.redis.RedisAdapter;
import com.iris.lite.java.shared.key.KeyStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link AgentKeyStore} 的 Lettuce 实现：动态 agent key 存 Redis HASH。
 *
 * <p><b>存储形态</b>：{@code iris:security:agent-keys}（全局 key，无 namespace），
 * field = 明文 key 的 SHA-256 十六进制指纹，value = 条目 JSON
 * {@code {"agentId":..,"tags":[..],"updatedAt":..}}。
 * <b>Redis 全程不接触明文 key</b>：指纹单向，即使 RDB/AOF 泄漏也不泄漏凭证本体；
 * 这是把 yml 明文配置搬进共享存储时必须补上的一层防护。
 *
 * <p><b>为什么全量 loadAll 而不是 HGET 单查</b>：鉴权过滤器每请求一次查询，
 * 单查会把鉴权延迟耦上一次 Redis RTT；注册表全量快照后本地比对是纯内存。
 * 条目量 = agent key 数（几十量级），HGETALL 全量拉取是合理刷新粒度。
 */
@Component
public class LettuceAgentKeyStore implements AgentKeyStore {

    private static final Logger log = LoggerFactory.getLogger(LettuceAgentKeyStore.class);

    private final RedisAdapter redis;
    private final KeyStrategy keyStrategy;
    private final ObjectMapper objectMapper;

    public LettuceAgentKeyStore(RedisAdapter redis,
                                KeyStrategy keyStrategy,
                                ObjectMapper objectMapper) {
        this.redis = redis;
        this.keyStrategy = keyStrategy;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, AgentKeyEntry> loadAll() {
        Map<String, String> raw = redis.hgetAll(keyStrategy.securityAgentKeysKey());
        Map<String, AgentKeyEntry> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            AgentKeyEntry decoded = decode(e.getValue());
            if (decoded != null) {
                // 指纹存在 HASH field 里而非 value JSON——这里以 field 为准重组条目，
                // 保证 fingerprint 字段恒非空（Map.copyOf 拒绝 null key，重组是防 NPE 的关键）
                result.put(e.getKey(), new AgentKeyEntry(
                        e.getKey(), decoded.agentId(), decoded.tags(), decoded.updatedAt()));
            }
        }
        return result;
    }

    @Override
    public void save(String fingerprint, String agentId, List<String> tags) {
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of(
                    "agentId", agentId,
                    "tags", tags,
                    "updatedAt", Instant.now().toString()));
        } catch (Exception e) {
            // Map.of 的值都是可序列化类型，理论不可达；兜底成明确失败而非半写状态
            throw new IllegalStateException("agent key 条目序列化失败", e);
        }
        redis.hset(keyStrategy.securityAgentKeysKey(), fingerprint, json);
        log.debug("agent key 条目已写入 fp={} agentId={}", fp8(fingerprint), agentId);
    }

    @Override
    public boolean delete(String fingerprint) {
        return redis.hdel(keyStrategy.securityAgentKeysKey(), fingerprint) > 0;
    }

    /**
     * 反序列化 value JSON（agentId/tags/updatedAt 三字段，不含指纹）。
     *
     * <p>解析成通用 Map 而非直接绑 {@link AgentKeyEntry}：该 record 的紧凑构造器
     * 拒绝空指纹（主键防错），而 value JSON 里本就没有指纹——直接绑定必然抛异常。
     * 指纹由 {@link #loadAll} 从 HASH field 补上。损坏条目跳过并告警
     * （HASH 里的坏行不应拖垮整个鉴权刷新）。
     */
    @SuppressWarnings("unchecked")
    private AgentKeyEntry decode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            String agentId = raw.get("agentId") == null ? "" : raw.get("agentId").toString();
            Object tagsRaw = raw.get("tags");
            List<String> tags = tagsRaw instanceof List<?> l
                    ? l.stream().map(String::valueOf).toList()
                    : List.of();
            String updatedAt = raw.get("updatedAt") == null ? null : raw.get("updatedAt").toString();
            // 指纹先用空串占位（构造器拒绝 null），loadAll 会以 HASH field 覆盖
            return new AgentKeyEntry("pending", agentId, tags, updatedAt);
        } catch (Exception e) {
            log.warn("agent key 条目反序列化失败，已跳过：{}", e.getMessage(), e);
            return null;
        }
    }

    private static String fp8(String fingerprint) {
        return fingerprint.length() <= 8 ? fingerprint : fingerprint.substring(0, 8);
    }
}

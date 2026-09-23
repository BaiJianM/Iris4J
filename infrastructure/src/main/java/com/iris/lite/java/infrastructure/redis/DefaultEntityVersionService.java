package com.iris.lite.java.infrastructure.redis;

import com.iris.lite.java.application.query.EntityVersionService;
import com.iris.lite.java.shared.key.KeyStrategy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体数据版本服务的 Redis 实现（数据版本守卫）。
 *
 * <p>版本计数器用 Redis 原生 INCR（{@code iris:{ns}:ver:{entity}}）：原子自增、
 * 无并发竞争；key 不存在从 0 起增（从未变更 = 版本 0）。
 */
@Component
public class DefaultEntityVersionService implements EntityVersionService {

    private final RedisAdapter redis;
    private final KeyStrategy keys;

    public DefaultEntityVersionService(RedisAdapter redis, KeyStrategy keys) {
        this.redis = redis;
        this.keys = keys;
    }

    @Override
    public long bump(String namespace, String entity) {
        return redis.incr(keys.entityVersionKey(namespace, entity));
    }

    @Override
    public long current(String namespace, String entity) {
        String v = redis.get(keys.entityVersionKey(namespace, entity));
        return v == null ? 0 : Long.parseLong(v);
    }

    @Override
    public Map<String, Long> currentAll(String namespace, List<String> entities) {
        if (entities.isEmpty()) {
            return Map.of();
        }
        // 一次 MGET 取回全部版本（守卫校验挂在查询/回答链路上，逐实体 GET 放大往返）
        String[] versionKeys = new String[entities.size()];
        for (int i = 0; i < entities.size(); i++) {
            versionKeys[i] = keys.entityVersionKey(namespace, entities.get(i));
        }
        List<String> values = redis.mget(versionKeys);
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < entities.size(); i++) {
            String v = values.get(i);
            out.put(entities.get(i), v == null ? 0L : Long.parseLong(v));
        }
        return out;
    }
}

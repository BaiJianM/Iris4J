package com.iris.lite.java.infrastructure.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.lite.java.application.query.RecipeDraftStore;
import com.iris.lite.java.shared.key.KeyStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 配方草稿池的 Redis list 实现（自进化循环）。
 *
 * <p>key = {@code keys.recipeDraftKey(ns)}（{@code iris:{ns}:recipe-drafts}）；
 * LPUSH 插队头 + LTRIM 封顶（「最近 N 条」审核队列）；条目为 RecipeDraft 的 JSON。
 * 存储异常一律吞掉只记日志——草稿是旁路增强，绝不影响回答主链路。
 */
@Component
public class LettuceRecipeDraftStore implements RecipeDraftStore {

    private static final Logger log = LoggerFactory.getLogger(LettuceRecipeDraftStore.class);

    private final RedisAdapter redis;
    private final KeyStrategy keys;
    private final ObjectMapper objectMapper;
    /** 池子封顶长度：审核队列不需要全量历史，200 条足够看出高频意图。 */
    private final int maxEntries;

    public LettuceRecipeDraftStore(
            RedisAdapter redis,
            KeyStrategy keys,
            ObjectMapper objectMapper,
            @Value("${iris.agent.recipe-drafts-max-entries:200}") int maxEntries) {
        this.redis = redis;
        this.keys = keys;
        this.objectMapper = objectMapper;
        this.maxEntries = Math.max(20, maxEntries);
    }

    @Override
    public void append(String namespace, RecipeDraft draft) {
        try {
            String json = objectMapper.writeValueAsString(draft);
            redis.lpushTrim(keys.recipeDraftKey(namespace), json, maxEntries);
        } catch (Exception e) {
            log.warn("配方草稿写入失败（不影响回答）ns={} err={}", namespace, e.getMessage(), e);
        }
    }

    @Override
    public List<RecipeDraft> list(String namespace, int limit) {
        List<RecipeDraft> out = new ArrayList<>();
        try {
            List<String> raw = redis.lrange(keys.recipeDraftKey(namespace), 0, Math.max(0, limit - 1));
            for (String json : raw) {
                try {
                    out.add(objectMapper.readValue(json, RecipeDraft.class));
                } catch (Exception e) {
                    log.warn("配方草稿解析失败（跳过该条）: {}", e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.warn("配方草稿读取失败 ns={} err={}", namespace, e.getMessage(), e);
        }
        return out;
    }

    @Override
    public long size(String namespace) {
        try {
            return redis.llen(keys.recipeDraftKey(namespace));
        } catch (Exception e) {
            return 0;
        }
    }
}

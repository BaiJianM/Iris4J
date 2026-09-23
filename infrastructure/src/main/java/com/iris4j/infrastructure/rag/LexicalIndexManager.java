package com.iris4j.infrastructure.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris4j.infrastructure.redis.RedisAdapter;
import com.iris4j.shared.key.KeyStrategy;
import com.iris4j.shared.util.LexTerms;
import io.lettuce.core.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAG 词法索引管理器（BM25 通道的存储侧）。
 *
 * <p><b>形态</b>：为长期记忆与 LLM 缓存各自的<b>文档前缀</b>建一个 FT 索引，
 * SCHEMA 只含两个派生字段——{@code $.lex TEXT}（词项化文本，BM25 打分依据）
 * 与业务过滤 TAG（{@code $.owner} / {@code $.modelTag}）。文档本体是权威 JSON，
 * 词法字段是伴生派生：save 时 {@code JSON.SET $.lex} 顺带写入（失败仅降级为
 * 词法通道漏此条，不影响权威数据）；删除/过期随文档自然消失，零额外清理。
 *
 * <p><b>存量回填</b>：索引首次创建时（返回 true 分支），把该前缀下已有文档
 * 的 lex 字段补齐——记忆走条目索引 SMEMBERS（去 SCAN 改造的既有结构），
 * LLM 缓存走条目 ZSET，都不碰全库 SCAN 红线。
 *
 * <p><b>惰性确保</b>：与 {@code MemoryVectorIndexManager} 同范式——FT.INFO
 * 探测 + 进程内缓存，外部删索引后下次调用自愈重建（重建触发重新回填）。
 */
@Component
public class LexicalIndexManager {

    private static final Logger log = LoggerFactory.getLogger(LexicalIndexManager.class);

    /** 单次回填批量 JSON.GET 的批大小（与记忆仓储 DOC_FETCH_BATCH 同量级）。 */
    private static final int BACKFILL_BATCH = 500;

    private final RedisAdapter redis;
    private final KeyStrategy keys;
    private final ObjectMapper objectMapper;

    /** 进程内已确保索引的 (surface|namespace) 缓存。 */
    private final Set<String> ensured = ConcurrentHashMap.newKeySet();

    public LexicalIndexManager(RedisAdapter redis, KeyStrategy keys, ObjectMapper objectMapper) {
        this.redis = redis;
        this.keys = keys;
        this.objectMapper = objectMapper;
    }

    /**
     * 确保长期记忆词法索引：{@code iris:{ns}:index:memory-lex}，
     * 前缀 = 长期记忆文档（{@code iris:{ns}:memory:long:}），
     * 字段 = {@code $.lex TEXT} + {@code $.owner TAG}（owner 精确预过滤）。
     */
    public void ensureMemoryIndex(String namespace) {
        String cacheKey = "memory|" + namespace;
        if (ensured.contains(cacheKey)) {
            return;
        }
        String index = keys.indexKey(namespace, "memory-lex");
        String prefix = keys.longTermMemoryKey(namespace, "");
        if (ensure(index, prefix, "owner") != EnsureResult.CREATED) {
            ensured.add(cacheKey);
            return;
        }
        backfillMemoryLex(namespace);
        ensured.add(cacheKey);
    }

    /** 记忆词法索引名（检索用）。 */
    public String memoryLexIndexName(String namespace) {
        return keys.indexKey(namespace, "memory-lex");
    }

    /**
     * 确保 LLM 缓存词法索引：{@code iris:{ns}:index:llmcache-lex}，
     * 前缀 = 缓存文档（{@code iris:{ns}:llmcache:}），
     * 字段 = {@code $.lex TEXT} + {@code $.modelTag TAG}。
     *
     * <p>前缀同样覆盖 {@code llmcache:vec:} 下的 HASH 向量 key——ON JSON 索引
     * 对非 JSON 文档直接跳过（无 JSON path 可取），不产生脏数据。
     */
    public void ensureLlmCacheIndex(String namespace) {
        String cacheKey = "llmcache|" + namespace;
        if (ensured.contains(cacheKey)) {
            return;
        }
        String index = keys.indexKey(namespace, "llmcache-lex");
        String prefix = keys.llmCacheKey(namespace, "", "");
        if (ensure(index, prefix, "modelTag") != EnsureResult.CREATED) {
            ensured.add(cacheKey);
            return;
        }
        backfillLlmCacheLex(namespace);
        ensured.add(cacheKey);
    }

    /** LLM 缓存词法索引名（检索用）。 */
    public String llmCacheLexIndexName(String namespace) {
        return keys.indexKey(namespace, "llmcache-lex");
    }

    /** 确保结果：EXISTS=索引已在（无需回填）、CREATED=当前调用新建（需回填）、FAILED=创建失败（不缓存，下次重试）。 */
    private enum EnsureResult {
        EXISTS, CREATED, FAILED
    }

    /**
     * 通用确保：FT.INFO 探测 → 不存在才 FT.CREATE。
     */
    private EnsureResult ensure(String index, String docPrefix, String tagAlias) {
        try {
            redis.ftInfo(index);
            return EnsureResult.EXISTS;
        } catch (RedisException e) {
            try {
                redis.ftCreate(index, docPrefix, List.of(
                        new RedisAdapter.FtField("$.lex", "lex", "TEXT", false),
                        new RedisAdapter.FtField("$." + tagAlias, tagAlias, "TAG", false)));
                log.info("词法索引已创建 index={} prefix={}（BM25 词法通道就绪）", index, docPrefix);
                return EnsureResult.CREATED;
            } catch (Exception ce) {
                // 创建失败不缓存：下次调用重试；词法通道缺失只影响召回面
                log.warn("词法索引创建失败（词法通道暂不可用）: index={} - {}", index, ce.getMessage());
                return EnsureResult.FAILED;
            }
        }
    }

    /**
     * 长期记忆 lex 字段存量回填：经条目索引（SET）枚举 id，批量 JSON.GET 检查
     * 是否已有 lex，缺则 JSON.SET $.lex 补写。失败仅告警——漏词法条目仍可被
     * 稠密通道与关键词兜底路径命中。
     */
    private void backfillMemoryLex(String namespace) {
        try {
            List<String> ids = new ArrayList<>(redis.smembers(keys.longTermMemoryIndexKey(namespace)));
            int filled = 0;
            for (int from = 0; from < ids.size(); from += BACKFILL_BATCH) {
                List<String> batch = ids.subList(from, Math.min(from + BACKFILL_BATCH, ids.size()));
                List<String> docKeys = batch.stream()
                        .map(id -> keys.longTermMemoryKey(namespace, id)).toList();
                List<String> jsons = redis.jsonGetPathBatch(docKeys, "$");
                for (int i = 0; i < jsons.size(); i++) {
                    String json = jsons.get(i);
                    if (json == null) {
                        continue; // 悬空成员：检索路径会惰性清理
                    }
                    JsonNode doc = objectMapper.readTree(RedisAdapter.unwrapJsonPathResult(json));
                    if (doc.hasNonNull("lex")) {
                        continue;
                    }
                    writeLex(docKeys.get(i), doc.path("content").asText(""));
                    filled++;
                }
            }
            log.info("记忆词法字段回填完成 ns={} 补写={}/{}", namespace, filled, ids.size());
        } catch (Exception e) {
            log.warn("记忆词法字段回填失败（旧记忆将由下次保存/重索引补齐）: ns={} - {}",
                    namespace, e.getMessage());
        }
    }

    /** LLM 缓存 lex 字段存量回填：经条目 ZSET（member=文档 key）枚举。 */
    private void backfillLlmCacheLex(String namespace) {
        try {
            List<String> docKeys = redis.zrange(keys.llmCacheIndexKey(namespace), 0, -1);
            int filled = 0;
            for (String docKey : docKeys) {
                String json = redis.get(docKey);
                if (json == null) {
                    continue; // 已过期：索引成员由惰性清理兜底
                }
                JsonNode doc = objectMapper.readTree(json);
                if (doc.hasNonNull("lex")) {
                    continue;
                }
                writeLex(docKey, doc.path("prompt").asText(""));
                filled++;
            }
            log.info("LLM 缓存词法字段回填完成 ns={} 补写={}/{}", namespace, filled, docKeys.size());
        } catch (Exception e) {
            log.warn("LLM 缓存词法字段回填失败: ns={} - {}", namespace, e.getMessage());
        }
    }

    /** 单条 lex 派生写：词项化 + JSON 字符串序列化（带引号才是合法 JSON 值）。 */
    private void writeLex(String docKey, String text) {
        try {
            String lexJson = objectMapper.writeValueAsString(LexTerms.toLexText(text));
            redis.jsonSetPath(docKey, "$.lex", lexJson);
        } catch (Exception e) {
            log.debug("lex 派生写失败 docKey={} - {}", docKey, e.getMessage());
        }
    }
}

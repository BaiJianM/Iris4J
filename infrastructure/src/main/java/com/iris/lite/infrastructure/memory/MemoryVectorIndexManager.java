package com.iris.lite.infrastructure.memory;

import com.iris.lite.cache.embedder.Embedder;
import com.iris.lite.infrastructure.redis.RedisAdapter;
import com.iris.lite.shared.key.KeyStrategy;
import io.lettuce.core.RedisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长期记忆向量索引管理器：为每个 (namespace, Embedder 指纹) 惰性确保
 * 一个 HNSW 向量索引存在，索引名 {@code iris:{ns}:index:memory-{fpHash}}。
 *
 * <p><b>为什么惰性而非启动时建</b>：记忆索引覆盖前缀
 * {@code iris:{ns}:memory:vec:{fpHash}:} 由 Embedder 指纹决定，而 namespace
 * 是请求级参数（不像实体 Schema 可以启动时全量枚举）。首次写入/检索该
 * namespace 时确保一次即可，之后进程内缓存住不再重复探测。
 *
 * <p><b>幂等与自愈</b>：以 FT.INFO 探测为准（而非只信进程内缓存），
 * FT.INFO 报"索引不存在"才 FT.CREATE——外部删索引（如人工清理）后
 * 下一次写入会自动重建，不会出现"索引没了还以为有"的静默丢检索。
 *
 * <p><b>指纹隔离（与语义缓存双索引切换同构）</b>：索引名与文档前缀都含指纹哈希，
 * 换 Embedder（模型或维度变化）后新向量进新索引；旧索引连同旧维度向量原地保留，
 * 不参与新检索——规避了"256 维向量混进 512 维 HNSW 图"的维度冲突。
 * 旧记忆条目的重嵌入：再次保存同 id 时自动覆盖新指纹向量；
 * 更完整的批量重嵌入策略暂未实现（需与记忆容量上限一并设计）。
 */
@Component
public class MemoryVectorIndexManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorIndexManager.class);

    /** 实体索引管理器用 entity 段命名；记忆索引复用同一 INDEX key 模板，实体段固定为 "memory-<schemaVersion><fpHash>"。 */
    private static final String INDEX_ENTITY_PREFIX = "memory-";

    /**
     * 长期记忆索引 schema 版本段（ownerId 层引入 owner TAG 时升级）。
     *
     * <p>schema 变更处理约定：ensureVectorIndex 只做存在性探测、不做属性比对，
     * 索引段名加版本号是最小侵入的升级路径——新段名触发全新 FT.CREATE，
     * FT 对 ON HASH PREFIX 下的<b>既有向量文档立即生效</b>（无需重写数据），
     * 旧版本索引原地闲置不参与检索。此后再改记忆索引 schema 时递增此版本号。
     */
    private static final String INDEX_SCHEMA_VERSION = "v2-";

    /** 工作记忆语义索引的实体段：{@code wm-<fpHash>}（与长期记忆索引隔离命名）。 */
    private static final String WORKING_INDEX_PREFIX = "wm-";

    /** LLM 响应缓存语义索引的实体段：{@code llmcache-<fpHash>}（与记忆索引隔离命名）。 */
    private static final String LLM_CACHE_INDEX_PREFIX = "llmcache-";

    private final RedisAdapter redis;
    private final KeyStrategy keys;
    private final ObjectProvider<Embedder> embedderProvider;

    /** 进程内已确保索引的 (namespace|fpHash) 缓存。FT.INFO 探测只在首次发生，后续零开销。 */
    private final Set<String> ensured = ConcurrentHashMap.newKeySet();

    public MemoryVectorIndexManager(
            RedisAdapter redis,
            KeyStrategy keys,
            ObjectProvider<Embedder> embedderProvider) {
        this.redis = redis;
        this.keys = keys;
        this.embedderProvider = embedderProvider;
    }

    /**
     * 确保 namespace 下当前指纹的记忆向量索引存在。
     *
     * <p>无 Embedder 时静默跳过（语义检索整体旁路的场景，调用方各自有兜底）。
     * FT.CREATE 失败（如 Redis 端异常）不缓存结果——下次调用重试；
     * 抛出异常交调用方决定降级（记忆检索会回退关键词路径）。
     */
    public void ensureIndex(String namespace) {
        Embedder embedder = embedderProvider.getIfAvailable();
        if (embedder == null) {
            return;
        }
        boolean created = ensureVectorIndex(namespace,
                INDEX_ENTITY_PREFIX + INDEX_SCHEMA_VERSION + embedder.fingerprintHash(),
                keys.memoryVectorKey(namespace, embedder.fingerprintHash(), ""),
                new String[]{"owner"});
        if (created) {
            backfillMissingOwner(namespace, embedder);
        }
    }

    /**
     * 旧向量 owner 回填（ownerId 层，仅新索引创建时执行一次）。
     *
     * <p>ownerId 层之前写入的向量 HASH 无 owner 字段，@owner 过滤会漏检它们；
     * 新索引创建（v2 段名首建/外部删索引自愈重建）后，对本前缀下的全部向量
     * 补 {@code owner=default}。写入路径的 hset("owner") 对此后新向量恒生效，
     * 本方法只为存量数据兜底。demo 规模开销可忽略；失败仅告警——漏检的旧记忆
     * 仍可经 keyword 兜底路径命中（本体 JSON 反序列化 owner 兜底 default）。
     */
    private void backfillMissingOwner(String namespace, Embedder embedder) {
        try {
            String pattern = keys.memoryVectorKey(namespace, embedder.fingerprintHash(), "*");
            int filled = 0;
            for (String vecKey : redis.scanKeys(pattern)) {
                if (redis.hget(vecKey, "owner") == null) {
                    redis.hset(vecKey, "owner", "default");
                    filled++;
                }
            }
            if (filled > 0) {
                log.info("长期记忆向量 owner 已回填 ns={} 补写={} 条（存量无主向量归 default）",
                        namespace, filled);
            }
        } catch (Exception e) {
            log.warn("长期记忆向量 owner 回填失败（存量记忆将走 keyword 兜底）: ns={} - {}",
                    namespace, e.getMessage());
        }
    }

    /**
     * 确保工作记忆语义索引存在。
     *
     * <p>与长期记忆索引的差异：PREFIX 段为 {@code iris:{ns}:memory:wm:{fpHash}:}，
     * 且 SCHEMA 额外声明 {@code session} TAG 字段——检索时用
     * {@code @session:{sid}=>[KNN ...]} 做会话内预过滤。
     */
    public void ensureWorkingIndex(String namespace) {
        Embedder embedder = embedderProvider.getIfAvailable();
        if (embedder == null) {
            return;
        }
        ensureVectorIndex(namespace, WORKING_INDEX_PREFIX + embedder.fingerprintHash(),
                keys.workingMemoryVectorPrefix(namespace, embedder.fingerprintHash()),
                new String[]{"session"});
    }

    /**
     * 确保 LLM 响应缓存语义索引存在。
     *
     * <p>PREFIX 段为 {@code iris:{ns}:llmcache:vec:{fpHash}:}，SCHEMA 额外声明
     * {@code model} TAG 字段——检索时 {@code @model:{tag}=>[KNN ...]} 做模型维度
     * 精确预过滤（不同模型的响应绝不互相命中）。
     */
    public void ensureLlmCacheIndex(String namespace) {
        Embedder embedder = embedderProvider.getIfAvailable();
        if (embedder == null) {
            return;
        }
        ensureVectorIndex(namespace, LLM_CACHE_INDEX_PREFIX + embedder.fingerprintHash(),
                keys.llmCacheVectorPrefix(namespace, embedder.fingerprintHash()),
                new String[]{"model"});
    }

    /**
     * 通用确保逻辑：FT.INFO 探测 → 不存在才 FT.CREATE，确保后进程内缓存。
     *
     * @return true=当前调用执行了 FT.CREATE（新索引，调用方可做一次性回填）；
     *         false=索引已存在（FT.INFO 命中）
     */
    private boolean ensureVectorIndex(String namespace, String segment, String docPrefix, String[] tagFields) {
        Embedder embedder = embedderProvider.getIfAvailable();
        if (embedder == null) {
            return false;
        }
        String cacheKey = namespace + "|" + segment;
        if (ensured.contains(cacheKey)) {
            return false;
        }
        String index = keys.indexKey(namespace, segment);
        try {
            redis.ftInfo(index);
            // FT.INFO 成功 = 索引已在，缓存住后续直通
            ensured.add(cacheKey);
            return false;
        } catch (RedisException e) {
            // 索引不存在（或探测失败）→ 尝试创建；创建成功才算确保
            redis.ftCreateVector(index, docPrefix, "vec", embedder.dimension(), tagFields);
            ensured.add(cacheKey);
            log.info("记忆向量索引已创建 ns={} index={} dim={} prefix={} tags={}",
                    namespace, index, embedder.dimension(), docPrefix, String.join(",", tagFields));
            return true;
        }
    }

    /** 当前指纹的记忆索引名（KNN 查询用）——段名必须与 ensureIndex 完全一致。 */
    public String indexName(String namespace) {
        return indexName(namespace, INDEX_ENTITY_PREFIX + INDEX_SCHEMA_VERSION + fingerprintHash());
    }

    /** 当前指纹的工作记忆索引名（KNN 查询用）。 */
    public String workingIndexName(String namespace) {
        return indexName(namespace, WORKING_INDEX_PREFIX + fingerprintHash());
    }

    /** 当前指纹的 LLM 响应缓存索引名（KNN 查询用）。 */
    public String llmCacheIndexName(String namespace) {
        return indexName(namespace, LLM_CACHE_INDEX_PREFIX + fingerprintHash());
    }

    private String indexName(String namespace, String segment) {
        Embedder embedder = embedderProvider.getIfAvailable();
        if (embedder == null) {
            throw new IllegalStateException("未配置 Embedder，无法定位记忆向量索引");
        }
        return keys.indexKey(namespace, segment);
    }

    /** 当前指纹的向量文档前缀（{@code iris:{ns}:memory:vec:{fpHash}:}，含尾部冒号）。 */
    public String vectorPrefix(String namespace) {
        Embedder embedder = embedderProvider.getIfAvailable();
        if (embedder == null) {
            throw new IllegalStateException("未配置 Embedder，无法定位记忆向量前缀");
        }
        // memoryVectorKey(ns, fpHash, "") 拼出 "iris:{ns}:memory:vec:{fpHash}:"，尾部冒号天然保留
        return keys.memoryVectorKey(namespace, fingerprintHash(), "");
    }

    /** 当前指纹的工作记忆向量前缀（{@code iris:{ns}:memory:wm:{fpHash}:}，含尾部冒号）。 */
    public String workingVectorPrefix(String namespace) {
        Embedder embedder = embedderProvider.getIfAvailable();
        if (embedder == null) {
            throw new IllegalStateException("未配置 Embedder，无法定位记忆向量前缀");
        }
        return keys.workingMemoryVectorPrefix(namespace, fingerprintHash());
    }

    /** 当前指纹哈希（LLM 缓存向量 key 的 fpHash 段用）。 */
    public String fingerprintHash() {
        return embedderProvider.getIfAvailable().fingerprintHash();
    }
}

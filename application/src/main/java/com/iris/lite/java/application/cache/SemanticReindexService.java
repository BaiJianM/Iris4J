package com.iris.lite.java.application.cache;

import com.iris.lite.java.application.query.SemanticCacheEntry;
import com.iris.lite.java.cache.embedder.Embedder;
import com.iris.lite.java.shared.util.Digests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语义缓存重嵌入服务：Embedder 指纹变化后，把旧指纹命名空间的条目迁移到当前命名空间。
 *
 * <p><b>机制</b>：
 * <ul>
 *   <li><b>索引切换</b>：语义条目 key 含 Embedder 指纹哈希段
 *       （{@code {entity}:sem:{fpHash}:{hardKey}:{semHash}}），换模型/维度后新写入
 *       自动进新命名空间，新旧索引天然隔离；</li>
 *   <li><b>重嵌入</b>：对旧命名空间条目仅重算 {@code filtersText} 的向量（查询结果
 *       {@code result} 原样迁移，无需重查询），写入新命名空间并删除旧 key；</li>
 *   <li><b>幂等</b>：迁移完成后旧命名空间清空，重复执行 migrated=0。</li>
 * </ul>
 *
 * <p>无指纹段的旧格式 key（{@code {entity}:sem:{hardKey}:{semHash}}，JSON 无
 * fingerprint 字段）同样按待迁移处理，保证平滑升级。
 */
@Service
public class SemanticReindexService {

    private static final Logger log = LoggerFactory.getLogger(SemanticReindexService.class);

    private final CacheService cacheService;
    private final ObjectProvider<Embedder> embedderProvider;
    private final long ttlSeconds;

    public SemanticReindexService(
            CacheService cacheService,
            ObjectProvider<Embedder> embedderProvider,
            @Value("${iris.cache.ttl-seconds:60}") long ttlSeconds) {
        this.cacheService = cacheService;
        this.embedderProvider = embedderProvider;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * 重嵌入报告。
     *
     * @param fromFingerprint 迁移来源指纹（{@code fromKeys>0} 时有效）
     * @param toFingerprint   当前 Embedder 指纹
     * @param scanned         扫描到的语义条目总数（含当前命名空间）
     * @param skipped         已属于当前指纹命名空间、无需迁移的条目数
     * @param migrated        成功迁移（重嵌入 + 写新 + 删旧）的条目数
     * @param failed          迁移失败条目数（旧条目保留，不丢数据）
     */
    public record ReindexReport(
            String fromFingerprint,
            String toFingerprint,
            int scanned,
            int skipped,
            int migrated,
            int failed) {
    }

    /**
     * 把 {@code namespace} 下 {@code entity} 的旧指纹语义条目重嵌入迁移到当前指纹命名空间。
     *
     * <p><b>key 段解析</b>（strip {@code iris:{ns}:cache:} 后按 {@code :} 切分，
     * entity 为标识符不含冒号）：
     * <ul>
     *   <li>4 段 {@code [entity, sem, hardKey, semHash]} = 旧格式；</li>
     *   <li>5 段 {@code [entity, sem, fp, hardKey, semHash]}：fp 等于当前指纹哈希 =
     *       已是当前索引（跳过），否则视为待迁移的旧指纹条目。</li>
     * </ul>
     *
     * <p><b>失败安全</b>：单条迁移失败只记 failed 并保留旧条目，不中断整批。
     * 迁移是幂等的，重跑即可补上失败的条目。
     */
    public ReindexReport reindex(String namespace, String entity) {
        Embedder embedder = embedderProvider.getIfAvailable();
        if (embedder == null) {
            throw new IllegalStateException("未配置 Embedder bean，无法重嵌入");
        }
        String currentFpHash = embedder.fingerprintHash();

        Map<String, SemanticCacheEntry> entries = cacheService.getAllMatchingWithKeys(
                namespace, entity + ":sem:*", SemanticCacheEntry.class);
        log.debug("重嵌入扫描 ns={} entity={} 条目={} 当前指纹={}",
                namespace, entity, entries.size(), embedder.fingerprint());

        int skipped = 0;
        int migrated = 0;
        int failed = 0;
        String fromFingerprint = null;

        for (Map.Entry<String, SemanticCacheEntry> e : entries.entrySet()) {
            String relKey = e.getKey();
            SemanticCacheEntry entry = e.getValue();

            String[] segments = relKey.split(":");
            // 段数不足或第二段不是 sem 标记 = 非语义缓存条目（可能是精确缓存串进来了）
            if (segments.length < 4 || !"sem".equals(segments[1])) {
                log.warn("重嵌入跳过无法识别的 key: {}", relKey);
                failed++;
                continue;
            }

            String hardKey;
            String semHash;
            String keyFpHash;
            if (segments.length == 4) {
                // 旧格式：[entity, sem, hardKey, semHash]
                keyFpHash = null;
                hardKey = segments[2];
                semHash = segments[3];
            } else {
                // 新格式：[entity, sem, fp, hardKey, semHash]
                keyFpHash = segments[2];
                hardKey = segments[3];
                semHash = segments[4];
            }
            // 已是当前指纹命名空间 = 无需迁移
            if (currentFpHash.equals(keyFpHash)) {
                skipped++;
                continue;
            }

            // 条目不完整（无语义文本或无查询结果）= 无法重嵌入，跳过
            if (entry.filtersText() == null || entry.filtersText().isBlank()
                    || entry.result() == null) {
                log.warn("重嵌入跳过不完整条目: {}", relKey);
                failed++;
                continue;
            }

            try {
                // 只重算向量，查询结果原样搬过去——不需要重新执行查询
                float[] vector = embedder.embed(entry.filtersText());
                String newKey = entity + ":sem:" + currentFpHash + ":"
                        + hardKey + ":" + Digests.sha256Hex(entry.filtersText()).substring(0, 12);
                cacheService.put(namespace, newKey,
                        new SemanticCacheEntry(entry.filtersText(), vector, entry.result(),
                                embedder.fingerprint(), OffsetDateTime.now().toString()),
                        ttlSeconds);
                // 新条目写成功后才删旧条目，避免中途失败导致条目丢失
                cacheService.invalidate(namespace, relKey);
                migrated++;
                if (fromFingerprint == null) {
                    fromFingerprint = entry.fingerprint() != null
                            ? entry.fingerprint() : "(legacy)";
                }
                log.info("重嵌入迁移: {} -> {}", relKey, newKey);
            } catch (Exception ex) {
                // 旧条目保留（未删），下次重试；单条失败不中断整批
                failed++;
                log.warn("重嵌入迁移失败，条目保留: {} - {}", relKey, ex.getMessage());
            }
        }

        ReindexReport report = new ReindexReport(
                fromFingerprint, embedder.fingerprint(), entries.size(), skipped, migrated, failed);
        log.info("语义缓存重嵌入完成: ns={} entity={} scanned={} skipped={} migrated={} failed={}",
                namespace, entity, entries.size(), skipped, migrated, failed);
        return report;
    }
}

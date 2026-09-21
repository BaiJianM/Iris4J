package com.iris.lite.infrastructure.cache;

import com.iris.lite.application.cache.CacheService;
import com.iris.lite.application.query.CachedEntityQueryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.lite.cache.CacheRepository;
import com.iris.lite.infrastructure.redis.RedisAdapter;
import com.iris.lite.shared.key.KeyStrategy;
import io.lettuce.core.RedisFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 基于 Redis 字符串命令（GET/SETEX/DEL/SCAN）的缓存实现：精确缓存 + 语义缓存条目存取。
 *
 * <p><b>为什么用 String 而不是 RedisJSON</b>：缓存条目是整读整写的黑盒，
 * 不需要按字段访问，用 String + JSON 最省事，且天然支持 TTL（SETEX）。
 *
 * <p>值用 JSON 序列化，带 TTL；序列化细节只在 infrastructure 层，
 * 上层的 {@code CacheService} 只看到 Java 对象。
 */
@Component
public class LettuceCacheRepository implements CacheRepository {

    private static final Logger log = LoggerFactory.getLogger(LettuceCacheRepository.class);

    /**
     * 实体缓存索引集合相对其成员的额外 TTL 缓冲（秒）。
     *
     * <p><b>为什么需要这个缓冲</b>：失效路径依赖「索引集合为空 ⟺ 该实体没有缓存条目」
     * 这个等价关系（为空时零成本返回，不再 SCAN）。要让它恒成立，索引集合的过期时刻
     * 必须晚于它索引的每一个条目：索引 TTL 取「条目 TTL + 本缓冲」，且每次写入都刷新。
     * 于是任一条目在 t 时刻写入、TTL=T，其过期时刻为 t+T，而索引在 t 时刻被续到
     * t+T+60——条目活跃期内索引必然存在。
     */
    private static final long CACHE_INDEX_TTL_BUFFER_SECONDS = 60;

    private final RedisAdapter redis;
    private final KeyStrategy keys;
    private final ObjectMapper objectMapper;

    public LettuceCacheRepository(
            RedisAdapter redis,
            KeyStrategy keys,
            ObjectMapper objectMapper) {
        this.redis = redis;
        this.keys = keys;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取缓存条目。
     *
     * <p>不打命中日志：精确缓存命中是最高频路径（每次查询都走），
     * 命中/未命中的判断由上层 {@code CachedEntityQueryService} 统一打点，
     * 避免同一件事在两层各打一次。
     */
    @Override
    public <T> Optional<T> get(String namespace, String key, Class<T> type) {
        String json = redis.get(keys.cacheKey(namespace, key));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, type));
        } catch (Exception e) {
            throw new IllegalStateException("缓存 JSON 解析失败", e);
        }
    }

    /**
     * 写入缓存（SETEX）+ 登记实体缓存索引。
     *
     * <p>TTL 是缓存新鲜度的兜底保障。索引登记是「失效能否 O(1)」的前提：
     * 每条缓存写入都把相对 key 段登记到 {@code iris:{ns}:cacheidx:{entity}}，
     * 之后 CDC 失效该实体时直接按集合成员删除，不必遍历整库。
     */
    @Override
    public void put(String namespace, String key, Object value, long ttlSeconds) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redis.setex(keys.cacheKey(namespace, key), ttlSeconds, json);
            indexCacheKey(namespace, key, ttlSeconds);
        } catch (Exception e) {
            throw new IllegalStateException("缓存序列化失败", e);
        }
    }

    /**
     * 把条目登记进所属实体的缓存索引集合。
     *
     * <p>entity 段从相对 key 的首段解析（{@code {entity}:{hash}} 与
     * {@code {entity}:sem:...} 两种形态首段都是 entity）。
     * 解析不出 entity 的 key 只打 debug 并跳过登记——这类条目仍可读可写，
     * 只是不被实体级失效覆盖，最终靠 TTL 过期，属于安全降级而非数据错误。
     */
    private void indexCacheKey(String namespace, String key, long ttlSeconds) {
        int sep = key.indexOf(':');
        if (sep <= 0) {
            log.debug("缓存 key 无 entity 段，跳过索引登记 ns={} key={}", namespace, key);
            return;
        }
        String entity = key.substring(0, sep);
        String indexKey = keys.cacheIndexKey(namespace, entity);
        redis.sadd(indexKey, key);
        // 索引必须比成员活得久，否则「集合为空 ⟹ 无缓存条目」的等价关系会破
        redis.expire(indexKey, ttlSeconds + CACHE_INDEX_TTL_BUFFER_SECONDS);
    }

    /**
     * 按「相对 key 段前缀」取索引集合成员。
     *
     * <p>与实体失效路径共用同一个索引集合 {@code iris:{ns}:cacheidx:{entity}}，
     * 这里只是额外做一次前缀过滤：集合成员是精确字符串，过滤是纯内存操作，
     * 代价只与集合大小（该实体缓存条目数）有关，与全库键数无关。
     *
     * @return 命中前缀的成员；<b>null 表示这条路走不通</b>（前缀里取不出 entity 段），
     *         调用方应回退 SCAN —— 宁可慢，也不能漏。
     */
    private Set<String> membersByPrefix(String namespace, String relativePrefix) {
        int sep = relativePrefix.indexOf(':');
        if (sep <= 0) {
            return null;
        }
        String entity = relativePrefix.substring(0, sep);
        Set<String> members = redis.smembers(keys.cacheIndexKey(namespace, entity));
        if (members == null || members.isEmpty()) {
            return Set.of();
        }
        Set<String> matched = new LinkedHashSet<>();
        for (String m : members) {
            if (m.startsWith(relativePrefix)) {
                matched.add(m);
            }
        }
        return matched;
    }

    /**
     * 把 Redis glob pattern 化简成「纯字面前缀」；化不了返回 null。
     *
     * <p>只接受 {@code 字面前缀 + 单个尾随 *} 这一种形态——索引集合成员是精确字符串，
     * 只能做前缀/等值过滤，表达不了 {@code a*b}、{@code ?}、{@code [abc]} 这类模式。
     * 化简不了就由调用方回退 SCAN：走对路比走得快重要。
     */
    private static String literalPrefixOrNull(String pattern) {
        if (pattern == null || !pattern.endsWith("*")) {
            return null;
        }
        String prefix = pattern.substring(0, pattern.length() - 1);
        if (prefix.indexOf('*') >= 0 || prefix.indexOf('?') >= 0 || prefix.indexOf('[') >= 0) {
            return null;
        }
        return prefix;
    }

    /** 失效单个缓存 key（DEL），并同步摘除索引成员。重嵌入迁移删旧条目时用。 */
    @Override
    public void invalidate(String namespace, String key) {
        redis.del(keys.cacheKey(namespace, key));
        int sep = key.indexOf(':');
        if (sep > 0) {
            // 同步摘成员：让索引规模与实际条目保持一致（残留成员本身无害，DEL 返回 0）
            redis.srem(keys.cacheIndexKey(namespace, key.substring(0, sep)), key);
        }
    }

    /**
     * 失效某实体下的全部查询缓存（精确缓存 + 语义缓存条目）。
     *
     * <p><b>这是 CDC 保证数据新鲜度的关键动作</b>：源库一行数据变更后，
     * 该实体所有相关查询结果都可能过期，靠 TTL 等自然过期会返回脏数据。
     *
     * <p><b>为什么不用 SCAN</b>：SCAN {@code iris:{ns}:cache:{entity}:*} 是
     * <b>整库游标遍历</b>、MATCH 只在服务端做结果过滤，代价是 O(全 keyspace 键数)
     * ——16 万键下单次 2.8 秒，且这份代价与"该实体到底有没有缓存"无关：
     * 缓存为空时遍历整库删空集，属于 100% 浪费（全量导入场景
     * {@code iris:ecomm:cache:*} 恒为 0 键）。
     *
     * <p>改为读 {@code iris:{ns}:cacheidx:{entity}} 索引集合：成员数 = 该实体缓存
     * 条目数（通常 &lt;100），成本与全库规模彻底解耦；集合为空即代表该实体
     * 从未写过缓存，直接返回，单次失效成本降到一次 SMEMBERS。
     */
    @Override
    public void invalidateEntity(String namespace, String entity) {
        String indexKey = keys.cacheIndexKey(namespace, entity);
        Set<String> members = redis.smembers(indexKey);
        if (members == null || members.isEmpty()) {
            // 索引缺席 ⟺ 该实体没有存活缓存条目（等价关系证明见 CACHE_INDEX_TTL_BUFFER_SECONDS）
            log.debug("实体缓存无需失效（无索引条目）ns={} entity={}", namespace, entity);
            return;
        }
        // 成员 key 一次 DEL 合并（CDC 失效热路径：逐成员 DEL 是 N 次往返）
        String[] memberKeys = members.stream()
                .map(relativeKey -> keys.cacheKey(namespace, relativeKey))
                .toArray(String[]::new);
        redis.del(memberKeys);
        redis.del(indexKey);
        // 打 debug：失效条数是验证"CDC 主动失效是否生效"的直接证据
        log.debug("实体缓存已失效 ns={} entity={} 清除条目={}", namespace, entity, members.size());
    }

    /** 按模式批量读取（只要值，不要 key）。语义缓存候选查找用。 */
    @Override
    public <T> List<T> getAllMatching(String namespace, String keyPattern, Class<T> type) {
        return List.copyOf(getAllMatchingWithKeys(namespace, keyPattern, type).values());
    }

    /**
     * 按模式批量读取，返回「相对 key 段 → 条目」映射。
     *
     * <p><b>相对段</b> = fullKey 剥掉 {@code iris:{ns}:cache:} 前缀，
     * 与 put/invalidate 的 key 参数同构，方便直接拿去删。
     *
     * <p><b>主路径走索引集合，不 SCAN 整库</b>：{@code SCAN MATCH
     * iris:{ns}:cache:{pattern}} 的代价恒为 O(全 keyspace)（MATCH 只在服务端过滤），
     * 346 万键下单次 5.3 秒——而语义缓存候选查找是<b>每次查询都要走</b>的路径。
     * 读实体索引集合 + 客户端前缀过滤 + 一次 MGET：
     * 代价为 O(该实体缓存条目数)，与全库规模彻底解耦。
     *
     * <p>回退条件：pattern 不是「纯字面前缀 + 单个 *」时化不成前缀，只能 SCAN。
     * 当前三个调用方传的都是 {@code {entity}:sem:{fp}:{hard}:*} 或 {@code {entity}:sem:*}，
     * 全部命中索引路径；回退分支是给未来调用方的安全网。
     */
    @Override
    public <T> Map<String, T> getAllMatchingWithKeys(String namespace, String keyPattern, Class<T> type) {
        Map<String, T> result = new LinkedHashMap<>();

        String literalPrefix = literalPrefixOrNull(keyPattern);
        Set<String> relativeKeys = literalPrefix == null ? null : membersByPrefix(namespace, literalPrefix);
        if (relativeKeys != null) {
            if (relativeKeys.isEmpty()) {
                // 索引为空 ⟺ 该实体没有存活缓存条目（等价关系见 CACHE_INDEX_TTL_BUFFER_SECONDS）
                log.debug("缓存模式查询(索引) ns={} pattern={} 命中=0", namespace, keyPattern);
                return result;
            }
            List<String> relativeList = new ArrayList<>(relativeKeys);
            String[] fullKeys = new String[relativeList.size()];
            for (int i = 0; i < relativeList.size(); i++) {
                fullKeys[i] = keys.cacheKey(namespace, relativeList.get(i));
            }
            List<String> jsons = redis.mget(fullKeys);
            for (int i = 0; i < relativeList.size(); i++) {
                String json = jsons.get(i);
                if (json == null) {
                    // 条目已 TTL 过期、索引成员还没摘：跳过即可（残留成员下次被自然清理）
                    continue;
                }
                try {
                    result.put(relativeList.get(i), objectMapper.readValue(json, type));
                } catch (Exception e) {
                    // 单条坏数据只跳过，不中断整批（旧格式/损坏条目不应拖垮查询）
                    log.warn("缓存条目解析失败，已跳过: {}", fullKeys[i]);
                }
            }
            log.debug("缓存模式查询(索引) ns={} pattern={} 成员={} 有效条目={}",
                    namespace, keyPattern, relativeList.size(), result.size());
            return result;
        }

        // 兜底：非纯前缀 pattern 只能全库 SCAN（代价 O(keyspace)，主路径必须避开）
        String prefix = keys.cacheKey(namespace, "");
        List<String> fullKeys = redis.scanKeys(keys.cacheKey(namespace, keyPattern));
        for (String fullKey : fullKeys) {
            String json = redis.get(fullKey);
            if (json == null) {
                continue;
            }
            try {
                result.put(fullKey.substring(prefix.length()), objectMapper.readValue(json, type));
            } catch (Exception e) {
                log.warn("缓存条目解析失败，已跳过: {}", fullKey);
            }
        }
        log.warn("缓存模式查询走 SCAN 兜底（pattern 无法化简为前缀，代价 O(全 keyspace)）"
                + " ns={} pattern={} 扫描={} 有效条目={}",
                namespace, keyPattern, fullKeys.size(), result.size());
        return result;
    }

    /**
     * 容量淘汰：把 {@code {entity}{segment}} 开头的条目裁到 keepEntries 以内。
     *
     * <p><b>淘汰顺序 = 剩余 TTL 最短优先</b>：语义条目写入时 TTL 统一（默认 60s），
     * 剩余寿命最短的条目就是最早写入的——这是 LRU 的低成本近似
     * （真 LRU 需要 ZSET 记录 lastHit 并在每次命中时刷新，多一次往返；
     * 对秒级 TTL 的语义缓存，"最旧写入"与"最久未用"的差距可忽略）。
     *
     * <p>PTTL 语义：-2 = key 已消失（TTL 过期还没清，直接算待淘汰）；
     * -1 = 无 TTL（异常状态，同样优先淘汰）。两者都排在有 TTL 条目之前。
     *
     * <p><b>候选集从索引集合取，不 SCAN 整库</b>：SCAN
     * {@code iris:{ns}:cache:{entity}{segment}*} 的代价恒为 O(全 keyspace)：
     * 该实体一条语义条目都没有时也要遍历 346 万键（单次 5.3 秒）。
     * 而调用方是「每 50 次 STORE 触发一次」的周期性动作，长期运行等于持续
     * 给 Redis 加载全库扫描；索引驱动后，无条目时一次 SMEMBERS 即返回。
     *
     * <p>逐条 PTTL 仍是 N 次往返，但 N 被索引限定为该实体的语义条目数（有上限），
     * 且调用方按写入次数抽样触发，不逐次执行。
     */
    @Override
    public int evictOldest(String namespace, String entity, String segment, int keepEntries) {
        String relativePrefix = entity + segment;
        Set<String> members = membersByPrefix(namespace, relativePrefix);
        List<String> relativeKeys;
        if (members != null) {
            // 成员数未超上限 ⟺ 无需淘汰，零成本返回
            if (members.size() <= keepEntries) {
                return 0;
            }
            relativeKeys = new ArrayList<>(members);
        } else {
            // 兜底：前缀里取不出 entity 段（异常入参），退回 SCAN —— 宁可慢也不漏淘汰
            String prefix = keys.cacheKey(namespace, "");
            relativeKeys = new ArrayList<>();
            for (String fullKey : redis.scanKeys(keys.cacheKey(namespace, relativePrefix + "*"))) {
                relativeKeys.add(fullKey.substring(prefix.length()));
            }
            if (relativeKeys.size() <= keepEntries) {
                return 0;
            }
        }

        int toEvict = relativeKeys.size() - keepEntries;
        // (score, index) 打分：-2/-1 给 MIN_VALUE，让升序排序时排最前（待淘汰优先级最高）。
        // PTTL 一次 pipeline 收割（逐条是 N 次往返）
        long[] pttls = new long[relativeKeys.size()];
        try {
            var async = redis.asyncCommands();
            List<RedisFuture<Long>> pttlFutures = new ArrayList<>(relativeKeys.size());
            for (int i = 0; i < relativeKeys.size(); i++) {
                pttlFutures.add(async.pttl(keys.cacheKey(namespace, relativeKeys.get(i))));
            }
            for (int i = 0; i < pttlFutures.size(); i++) {
                try {
                    pttls[i] = pttlFutures.get(i).get();
                } catch (Exception e) {
                    pttls[i] = -1;
                }
            }
        } catch (Exception e) {
            // pipeline 整体不可用：全部按「已过期/无 TTL」最优先处理，行为不劣化
            java.util.Arrays.fill(pttls, -1);
        }
        List<long[]> scored = new ArrayList<>(relativeKeys.size());
        for (int i = 0; i < relativeKeys.size(); i++) {
            scored.add(new long[]{pttls[i] < 0 ? Long.MIN_VALUE : pttls[i], i});
        }
        scored.sort((a, b) -> Long.compare(a[0], b[0]));

        // 淘汰列表一次 DEL + 一次 SREM 合并（原先逐 key 两两往返 3N 次）
        List<String> victims = new ArrayList<>(toEvict);
        for (int i = 0; i < toEvict; i++) {
            victims.add(relativeKeys.get((int) scored.get(i)[1]));
        }
        try {
            redis.del(victims.stream()
                    .map(relativeKey -> keys.cacheKey(namespace, relativeKey))
                    .toArray(String[]::new));
            // 同步摘索引成员：淘汰后索引必须跟上，否则 SMEMBERS 只增不减，
            // 下一轮淘汰会拿着越来越长的「幽灵成员」清单重复打分
            redis.srem(keys.cacheIndexKey(namespace, entity), victims.toArray(String[]::new));
            log.info("语义缓存容量淘汰 ns={} entity{} 存量={} 上限={} 淘汰={}",
                    namespace, segment, relativeKeys.size(), keepEntries, victims.size());
            return victims.size();
        } catch (Exception e) {
            log.warn("缓存容量淘汰批量删除失败 ns={} entity{} 淘汰目标={} 个: {}",
                    namespace, segment, victims.size(), e.getMessage());
            return 0;
        }
    }
}

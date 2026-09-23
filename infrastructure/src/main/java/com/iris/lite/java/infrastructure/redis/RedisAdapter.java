package com.iris.lite.java.infrastructure.redis;

import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.Consumer;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.models.stream.PendingMessage;
import io.lettuce.core.output.ArrayOutput;
import io.lettuce.core.output.ByteArrayOutput;
import io.lettuce.core.output.IntegerOutput;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.output.ValueOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import io.lettuce.core.protocol.ProtocolKeyword;
import com.iris.lite.java.shared.error.ErrorCode;
import com.iris.lite.java.shared.error.IrisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis 命令适配器：把业务需要的 Redis 操作集中到 infrastructure 层。
 *
 * <p><b>为什么需要这一层</b>：项目用原生 Lettuce（而非 spring-data-redis），
 * 因为要用到 RedisJSON 的 {@code JSON.SET/JSON.GET} 和 Stream 的
 * XCLAIM/XPENDING 等 spring-data 没有友好封装的命令。
 * 但 Lettuce 的 API（CommandArgs / CommandType / ScanCursor 等）绝不能泄漏到业务层——
 * 否则换客户端就是全链路改造。本类就是那道隔离墙：对外只暴露
 * {@code jsonSet(key, json)} 这种业务语义方法。
 *
 * <p><b>双连接模型（关键设计）</b>：
 * <ul>
 *   <li>{@code connection}（主连接）：查询、投影、缓存、运维等普通命令；</li>
 *   <li>{@code cdcConnection}：Stream 相关的<b>非阻塞</b>命令（XRANGE/XPENDING/XCLAIM/
 *       XADD/XDEL/XACK）。与阻塞读分开，避免阻塞读把运维命令一起饿死。</li>
 * </ul>
 * 而阻塞读（XREADGROUP BLOCK）会<b>独占整条连接</b>直到超时返回，
 * 因此每个 CDC 消费线程还要通过 {@link #openBlockingSession()} 再开一条独立连接。
 * 多源场景下若共享一条连接，BLOCK 命令会串行排队，后到的源迟迟读不到消息，
 * 最终撞上 {@code redis.timeout-ms} 报命令超时——这是踩过的坑。
 *
 * <p><b>日志策略</b>：高频命令（JSON.GET/SET/GET/SETEX/DEL）不打日志——
 * 一次过滤查询可能触发上百次 JSON.GET，打日志等于把业务日志淹没。
 * 只在<b>有诊断价值的节点</b>打 debug：连接生命周期、SCAN 规模、
 * 以及全部 Stream 流程命令（CDC 可靠性链路的核心）。
 */
@Component
public class RedisAdapter {

    private static final Logger log = LoggerFactory.getLogger(RedisAdapter.class);

    private final RedisClient client;
    /** 主连接：查询/投影/缓存/运维等普通命令。 */
    private final StatefulRedisConnection<String, String> connection;
    /** CDC 专用连接：Stream 非阻塞命令。与阻塞读连接分开，避免互相影响。 */
    private final StatefulRedisConnection<String, String> cdcConnection;

    /** 全部存活阻塞会话登记表：停机时逐一强制关闭（防 SIGTERM 僵尸）。 */
    private final List<BlockingSession> blockingSessions = new CopyOnWriteArrayList<>();

    public RedisAdapter(
            RedisClient client,
            @Qualifier("redisConnection") StatefulRedisConnection<String, String> connection,
            @Qualifier("cdcRedisConnection") StatefulRedisConnection<String, String> cdcConnection) {
        this.client = client;
        this.connection = connection;
        this.cdcConnection = cdcConnection;
    }

    /** 主连接的同步命令接口。 */
    private RedisCommands<String, String> sync() {
        return connection.sync();
    }

    /** CDC 连接的同步命令接口（非阻塞 Stream 命令走这里）。 */
    private RedisCommands<String, String> cdcSync() {
        return cdcConnection.sync();
    }

    /**
     * 批量投影 pipeline 的异步命令接口。
     *
     * <p>与 {@link #jsonGetPathBatch} 同一条主连接的 async 视图：dispatch 只入队不等待，
     * 调用方持有 {@code RedisFuture} 列表统一收割——把 N 次往返压成 1 次。
     */
    public RedisAsyncCommands<String, String> asyncCommands() {
        return connection.async();
    }

    /**
     * JSON.SET key $ json：整文档写入/覆盖。
     *
     * <p>路径参数固定为 {@code $}（文档根），即整覆盖而非局部更新。
     * 不打日志：CDC 投影每条变更都调一次，量太大。
     */
    public void jsonSet(String key, String json) {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                .add(key).add("$").add(json);
        sync().dispatch(CommandType.JSON_SET, new StatusOutput<>(StringCodec.UTF8), args);
    }

    /**
     * JSON.SET key path value：路径级局部写入（RAG 存入侧 {@code lex} 派生字段用）。
     *
     * <p>与 {@link #jsonSet} 的差异：只更新文档内一个路径，不动其余字段——
     * 记忆/LLM 缓存条目是业务权威 JSON（Jackson 序列化产物），词法派生字段
     * 不能通过整文档覆盖写（会把调用方不感知的字段洗掉），必须局部 SET。
     * value 必须是合法 JSON（字符串要带引号，由调用方 Jackson 序列化）。
     *
     * <p>TTL 语义：对带 TTL 的 key 做 JSON.SET 不重置 TTL（Redis 服务端行为），
     * 伴生写不会延长条目寿命。
     */
    public void jsonSetPath(String key, String path, String valueJson) {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                .add(key).add(path).add(valueJson);
        sync().dispatch(CommandType.JSON_SET, new StatusOutput<>(StringCodec.UTF8), args);
    }

    /**
     * JSON.GET key：读取整文档，返回 JSON 字符串；key 不存在返回 null。
     *
     * <p>不打日志：过滤查询会对每个 SCAN 命中的 key 调一次，日志量不可控。
     */
    public String jsonGet(String key) {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8).add(key);
        return sync().dispatch(CommandType.JSON_GET, new ValueOutput<>(StringCodec.UTF8), args);
    }

    /**
     * JSON.GET key path 批量版（维度归并）：同连接 pipeline 顺序发 N 个带 path 的
     * JSON.GET，返回值与入参按位置对应（key 不存在 → null）。
     *
     * <p>只取单字段 path（如 {@code $.channel_code}）而非整文档：归并只需一个维度值，
     * 大维表场景省 95%+ 的网络负载。调用方需控制批量大小（配置项 fetch-batch，默认 500）。
     * 打 debug 日志（批量调用频次低，只在归并查询出现）。
     */
    public List<String> jsonGetPathBatch(List<String> keys, String jsonPath) {
        List<io.lettuce.core.RedisFuture<String>> futures = new ArrayList<>(keys.size());
        for (String key : keys) {
            CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                    .add(key).add(jsonPath);
            futures.add(connection.async().dispatch(
                    CommandType.JSON_GET, new ValueOutput<>(StringCodec.UTF8), args));
        }
        List<String> values = new ArrayList<>(keys.size());
        for (io.lettuce.core.RedisFuture<String> f : futures) {
            try {
                values.add(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RedisException("JSON.GET 批量读取被中断", e);
            } catch (Exception e) {
                throw new RedisException("JSON.GET 批量读取失败: " + e.getMessage(), e);
            }
        }
        log.debug("JSON.GET pipeline 完成 keys={} path={}", keys.size(), jsonPath);
        return values;
    }

    /** DEL key：删除投影/缓存/记忆。不打日志（高频）。 */
    public void del(String key) {
        sync().del(key);
    }

    /** 多 key 批量删除：一次往返（DEL 原生 varargs），缓存失效等批量场景用。 */
    public long del(String... keys) {
        return sync().del(keys);
    }

    /**
     * SADD：向集合追加成员，返回新增个数（已存在的成员不重复计入）。
     *
     * <p>实体缓存索引写入路径。不打日志（每次缓存写入都调，高频）。
     */
    public long sadd(String key, String... members) {
        return sync().sadd(key, members);
    }

    /**
     * SMEMBERS：读取集合全部成员（Lettuce 返回 {@link Set}）。
     *
     * <p>实体缓存索引读取：成员数 = 该实体缓存条目数，量级小（通常 &lt;100），
     * 全量拉取成本可忽略——这正是替代全 keyspace SCAN 的关键。
     * 不打日志（CDC 每次投影都调，高频）。
     */
    public Set<String> smembers(String key) {
        return sync().smembers(key);
    }

    /**
     * SREM：从集合移除成员，返回实际移除数。
     *
     * <p>单键失效后同步索引，避免集合留下指向已消失 key 的悬空成员。
     * 悬空成员本身无害（DEL 返回 0），此处的 SREM 只为让索引规模保持准确。
     */
    public long srem(String key, String... members) {
        return sync().srem(key, members);
    }

    /**
     * LPUSH + LTRIM：列表头部写入并截断长度（配方自进化草稿池）。
     *
     * <p>草稿池是「最近 N 条」语义的审核队列：新样本插队头，池子封顶裁尾，
     * 防长跑实例无限增长。原子性要求不高（丢一条草稿无害），不必用事务。
     */
    public void lpushTrim(String key, String value, long keep) {
        sync().lpush(key, value);
        sync().ltrim(key, 0, keep - 1);
    }

    /** LRANGE：读取列表片段（草稿池审核视图）。start/end 为 Redis 闭区间语义（-1 = 末尾）。 */
    public List<String> lrange(String key, long start, long end) {
        return sync().lrange(key, start, end);
    }

    /** LLEN：列表长度（草稿池容量指标）。 */
    public long llen(String key) {
        return sync().llen(key);
    }

    /**
     * EXPIRE key seconds：为已存在的 key 设置相对 TTL。
     *
     * <p>典型用途是工作记忆会话：key 在首次写入时创建，之后每次追加/读取
     * 刷新 TTL 实现"活跃会话自动续期，僵尸会话自动过期"。
     * 不打日志（高频）。key 不存在时 EXPIRE 返回 0 且无副作用，天然幂等。
     */
    public void expire(String key, long seconds) {
        sync().expire(key, seconds);
    }

    /**
     * ZADD：向有序集合写入/更新成员分数，返回<b>新增</b>成员数（已存在只更新分数，不计新增）。
     *
     * <p>LLM 缓存条目索引用：{@code score = 条目过期的 epoch 毫秒}，
     * member 为条目 key。这样"条目数"用 {@code ZCARD} 取得（O(1)）、
     * "淘汰谁"用 {@code ZRANGE} 按分数升序取得（O(logN)）——彻底取代
     * 统计/淘汰时的全 keyspace SCAN。打分用绝对过期时刻而非 TTL 秒数，
     * 是为了让索引自身就能按"谁先过期"排序，无需再逐条 PTTL。
     * 不打日志（每次缓存写入都调，高频）。
     */
    public long zadd(String key, double score, String member) {
        return sync().zadd(key, score, member);
    }

    /**
     * ZCARD：有序集合成员数。集合不存在返回 0。
     *
     * <p>LLM 缓存条目计数。注意调用方须先用 {@link #zremrangebyscore}
     * 惰性清理已过期成员，否则计数会包含"文档 key 已被 TTL 删掉、索引成员还在"的悬空项。
     */
    public long zcard(String key) {
        return sync().zcard(key);
    }

    /**
     * ZRANGE key start stop：按分数升序取成员（含两端，{@code 0 -1} 取全部）。
     *
     * <p>取"过期最早"的待淘汰条目，以及索引成员全量列举
     * （成员数上限=缓存 max-entries，量级小，全量拉取成本可忽略）。
     */
    public List<String> zrange(String key, long start, long stop) {
        return sync().zrange(key, start, stop);
    }

    /** ZREM：移除成员，返回实际移除数。条目删除/淘汰后同步索引，避免悬空成员。 */
    public long zrem(String key, String... members) {
        return sync().zrem(key, members);
    }

    /**
     * ZREMRANGEBYSCORE key min max：按分数区间删除成员，返回删除数。
     *
     * <p>惰性清理用：{@code ("-inf", 当前毫秒)} 摘掉所有"过期时刻已到"的索引成员。
     * 之所以必须做：索引成员的过期不受 Redis 单项 TTL 管辖（ZSET 无逐成员 TTL），
     * 文档 key 到期自动消失后索引会留下悬空成员，计数与淘汰只能靠这一步校正。
     */
    public long zremrangebyscore(String key, String min, String max) {
        return sync().zremrangebyscore(key, min, max);
    }

    /** GET key，返回字符串；key 不存在返回 null。不打日志（缓存命中路径，高频）。 */
    public String get(String key) {
        return sync().get(key);
    }

    /**
     * MGET：一次取多个 key，返回<b>与入参等长且顺序一致</b>的列表，
     * 不存在的 key 在对应位置为 {@code null}。
     *
     * <p>语义缓存候选读取用：候选条目数可达上限（默认 1000），
     * 逐条 GET 会把一次查询放大成上千次往返，MGET 把它压成一次。
     *
     * <p><b>不能用 {@code Stream.toList()} 收集</b>：它对 null 元素直接抛 NPE，
     * 而"key 已不存在"恰恰是缓存场景的常态（条目 TTL 过期了、索引成员还没摘）。
     */
    public List<String> mget(String... keys) {
        if (keys == null || keys.length == 0) {
            return List.of();
        }
        List<String> values = new ArrayList<>(keys.length);
        for (KeyValue<String, String> kv : sync().mget(keys)) {
            values.add(kv.getValue());
        }
        return values;
    }

    /**
     * SETEX key seconds value：写入并设置过期时间。
     *
     * <p>TTL 是缓存正确性的兜底：即使 CDC 主动失效因故没执行，
     * 条目最多存活 ttl-seconds 秒。不打日志（高频）。
     */
    public void setex(String key, long seconds, String value) {
        sync().setex(key, seconds, value);
    }

    /**
     * SCAN 匹配 pattern，返回全部命中的 key。
     *
     * <p><b>性能警告（务必保留）</b>：SCAN 的 MATCH 只在服务端做结果过滤，
     * <b>不减少遍历量</b>，代价恒为 O(整个 keyspace)。ecomm 数据集 346 万键下单次
     * 完整遍历为秒级（redis-cli 测量约 5.3 秒，SLOWLOG 记录 2.9s 级），因此本方法
     * <b>禁止出现在 CDC 逐条变更、或每次查询都要走的路径上</b>：
     * <ul>
     *   <li>实体缓存失效走「索引集合驱动」（invalidateEntity）；</li>
     *   <li>语义缓存候选读取与容量淘汰走索引驱动
     *       （getAllMatchingWithKeys / evictOldest）。</li>
     * </ul>
     * 目前仅剩两个合法用途：控制台键浏览的有界 SCAN（{@code scanKeys(pattern, limit)}），
     * 以及 {@code getAllMatchingWithKeys} 遇到「非纯前缀 pattern」时的兜底回退。
     *
     * <p><b>已知陷阱</b>：不能用 {@code ScanCursor.of(x).isFinished()}
     * 判断遍历结束。Lettuce 7.5.2 的 {@code ScanCursor.of(String)} 只设置 cursor
     * 字符串、<b>不设置 finished 标志</b>，导致 {@code isFinished()} 永远返回 false，
     * 循环无法退出——表现为内存持续增长直到 OOM。
     * 正确做法是用 scan <b>返回的</b> cursor 字符串判断：等于 {@code "0"} 表示遍历完成。
     *
     * <p>打 debug 日志：SCAN 是全库遍历操作，条目数直接反映数据量与查询代价，
     * 排查"查询为什么慢"时这是第一个要看的数字。
     */
    public List<String> scanKeys(String pattern) {
        List<String> keys = new ArrayList<>();
        String cursor = "0";
        do {
            var result = sync().scan(
                    ScanCursor.of(cursor),
                    ScanArgs.Builder.matches(pattern).limit(1000));
            keys.addAll(result.getKeys());
            // 不能用 ScanCursor.of(x).isFinished() 判断结束——Lettuce 的 of() 只设置
            // cursor 字符串、不设置 finished 标志（7.5.2 反编译确认），会死循环并 OOM。
            // 这里用 scan 返回的下一个 cursor 字符串判断，cursor=="0" 表示遍历完成。
            cursor = result.getCursor();
        } while (!"0".equals(cursor));
        log.debug("SCAN 完成 pattern={} 命中={}", pattern, keys.size());
        return keys;
    }

    /**
     * SCAN 有界版（控制台键浏览用）：collect 到 limit 条即提前返回。
     *
     * <p>键浏览不需要全量遍历——控制台分页看前 N 条即可，全库 SCAN 在
     * 大 keyspace 下是无界耗时。提前停止时 cursor 未到 "0"，
     * truncated=true 告知调用方还有更多。
     */
    public ScanPage scanKeys(String pattern, int limit) {
        List<String> keys = new ArrayList<>();
        String cursor = "0";
        boolean truncated = false;
        do {
            var result = sync().scan(
                    ScanCursor.of(cursor),
                    ScanArgs.Builder.matches(pattern).limit(Math.min(1000, Math.max(10, (limit - keys.size()) * 2))));
            keys.addAll(result.getKeys());
            cursor = result.getCursor();
            if (keys.size() >= limit) {
                // 攒够即停：一批可能超量，先截断到 limit；cursor 未走完说明后面还有
                truncated = !"0".equals(cursor);
                keys = keys.subList(0, Math.min(keys.size(), limit));
                break;
            }
        } while (!"0".equals(cursor));
        log.debug("SCAN(有界) 完成 pattern={} 命中={} truncated={}", pattern, keys.size(), truncated);
        return new ScanPage(new ArrayList<>(keys), truncated);
    }

    /** 有界 SCAN 结果：keys + 是否还有更多（truncated）。 */
    public record ScanPage(List<String> keys, boolean truncated) {
    }

    /** TYPE key：返回类型字符串（string/hash/json/stream/zset/set/list/none）。 */
    public String type(String key) {
        return sync().type(key);
    }

    /** HGETALL key：返回整个 hash；key 不存在返回空 map。键浏览只读用。 */
    public Map<String, String> hgetall(String key) {
        return sync().hgetall(key);
    }

    /** PING：探活。不打日志（健康指示器每几秒调一次）。 */
    public String ping() {
        return sync().ping();
    }

    /**
     * XRANGE 读取 stream 指定区间。
     *
     * <p>用于 DLQ 条目列举与毒消息转存前的原始消息回读。走 cdc 连接。
     */
    public List<StreamMessage<String, String>> xrange(
            String streamKey, String start, String end, int count) {
        return cdcSync().xrange(streamKey, Range.create(start, end),
                Limit.from(count));
    }

    /** XLEN stream 长度。 */
    public long xlen(String streamKey) {
        return cdcSync().xlen(streamKey);
    }

    /**
     * XTRIM：按 MAXLEN 近似裁剪 stream（消费完的历史事件不再有保留价值）。
     *
     * <p>approximateTrimming=true（节点随机裁到略超 maxlen），均摊 O(1)，
     * 不阻塞同 key 的写入侧（Debezium 持续 XADD）。
     *
     * <p><b>调用方契约：maxlen 必须 ≥ 未消费量 + 未确认量 + 余量。</b>
     * XTRIM MAXLEN 只认「保留最新 N 条」，<b>不区分是否已消费</b>；传一个小于积压的
     * maxlen 会把尚未投递的事件直接删掉，而被删的消息不在 PEL 里，重试与 DLQ 都兜不住
     * ——静默丢数据。典型风险量级：单实例全量快照产出速率（约 2.5 万行/秒）远超消费
     * 速率（约 2500 条/秒），maxlen=100000 时一次快照即可丢十几万条。
     * 本方法只提供机制，<b>安全上限的估算在 CdcConsumer.trimStream</b>（取
     * {@code max(streamMaxlen, lag + pending + 余量)}），新调用方不要绕过它直接传配置值。
     *
     * @return 实际裁掉的消息数（近似裁剪为下限估计）
     */
    public long xtrim(String streamKey, long maxlen) {
        return cdcSync().xtrim(streamKey, true, maxlen);
    }

    /**
     * XPENDING 摘要计数（Gauge 用）：返回 group 的 PEL 总条数。
     *
     * <p>用摘要形式（不带 range）——Redis 返回 count/first/last/consumer 统计，
     * 只取 count。PEL 通常很小（正常链路消息处理完就 XACK），抓取成本可忽略。
     * stream/group 不存在时 Lettuce 会抛异常，由 Gauge 侧按 0 处理。
     */
    public long xpendingCount(String streamKey, String group) {
        return cdcSync().xpending(streamKey, group).getCount();
    }

    /**
     * XINFO GROUPS：读取指定消费组的 lag（尚未投递给该组的事件数 = 真实积压）。
     *
     * <p><b>为什么不用 XLEN 当积压</b>：Debezium 消费后不删除事件，XLEN 反映的是
     * 当前保留在 stream 里的条数（未开启裁剪时等于历史累计、只增不减），
     * 与"落后多少没消费"是两个标准——控制台标准必须用 lag
     * （lag 才反映真实积压；XLEN 仅作保留窗口内的事件量参考）。
     *
     * <p>应答形态随 RESP 协议不同：RESP3 每个 group 是 map，RESP2 是扁平数组
     * {@code [name, g, consumers, n, pending, n, ..., lag, n]}。两种都解析
     * （与 FT.INFO 解析同一套处理思路）。
     *
     * @return 该 group 的 lag；stream 不存在（报错抛出，调用方 safe 包装）/
     *         group 不存在 / Redis 版本过老无 lag 字段时返回 null
     */
    public Long xinfoGroupLag(String streamKey, String group) {
        List<Object> groups = cdcSync().xinfoGroups(streamKey);
        for (Object g : groups) {
            Map<String, Object> m = toFlatMap(g);
            if (m == null || !group.equals(text(m.get("name")))) {
                continue;
            }
            Object lag = m.get("lag");
            if (lag instanceof Number n) {
                return n.longValue();
            }
            if (lag != null) {
                try {
                    return Long.parseLong(text(lag));
                } catch (NumberFormatException ignored) {
                    // 异常形态当无 lag 处理
                }
            }
            return null;
        }
        return null;
    }

    /**
     * XINFO CONSUMERS：返回「消费端名 → 未确认条数（pending）」映射。
     *
     * <p>陈旧消费端剪枝用。消费端 id 带上实例标识后，每次重启都会在 group 里
     * 留下一个新条目，而 Redis <b>从不自动回收</b>——不清理的话 XINFO CONSUMERS
     * 只增不减，135 条流各自累积，监控里看到的消费端数量将失去意义。
     */
    public Map<String, Long> xinfoConsumers(String streamKey, String group) {
        List<Object> consumers = cdcSync().xinfoConsumers(streamKey, group);
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object c : consumers) {
            Map<String, Object> m = toFlatMap(c);
            if (m == null) {
                continue;
            }
            Object pending = m.get("pending");
            long p = pending instanceof Number n ? n.longValue() : 0L;
            out.put(text(m.get("name")), p);
        }
        return out;
    }

    /**
     * XGROUP DELCONSUMER：从 group 删除一个消费端。
     *
     * <p><b>副作用必须知道</b>：该消费端在 PEL 里的条目会被一并丢弃，那些消息从此
     * 不再被重投（也不会进 DLQ）——等价于静默丢消息。因此调用方
     * <b>只能在 pending == 0 时调用</b>，那时没有任何东西可丢。
     */
    public long xgroupDelConsumer(String streamKey, String group, String consumer) {
        return cdcSync().xgroupDelconsumer(streamKey, Consumer.from(group, consumer));
    }

    /** 把 XINFO 的单个 group 元素（RESP3 map / RESP2 扁平数组）统一成键值 map。 */
    private static Map<String, Object> toFlatMap(Object o) {
        if (o instanceof Map<?, ?> raw) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : raw.entrySet()) {
                out.put(text(e.getKey()), e.getValue());
            }
            return out;
        }
        if (o instanceof List<?> flat) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                out.put(text(flat.get(i)), flat.get(i + 1));
            }
            return out;
        }
        return null;
    }

    /** RESP 元素转文本：byte[]（某些编解码路径）按 UTF-8，其余走 toString。 */
    private static String text(Object o) {
        return o instanceof byte[] b ? new String(b, java.nio.charset.StandardCharsets.UTF_8)
                : String.valueOf(o);
    }

    /**
     * PTTL：返回 key 剩余存活毫秒（T8 容量淘汰用）。
     *
     * <p>Redis 语义：-2 = key 不存在；-1 = key 存在但无 TTL。
     * 这两个负值由调用方（淘汰排序）自行处理，这里原样返回。
     */
    public long pttl(String key) {
        return sync().pttl(key);
    }

    // ---- CDC 可靠性运维命令（非阻塞，走 cdc 连接）----

    /**
     * XPENDING 概要：读取 group 的 PEL（Pending Entries List）。
     *
     * <p>PEL 里是"已投递但未被 XACK"的消息——也就是处理失败的消息。
     * 毒消息防护机制就是靠周期扫描 PEL 来发现并重试/转移这些消息。
     */
    public List<PendingMessage> xpending(String streamKey, String group, int limit) {
        List<PendingMessage> pending =
                cdcSync().xpending(streamKey, group, Range.unbounded(), Limit.from(limit));
        // 只在有积压时打日志：无 pending 是常态，每 5 秒一次的空扫描不该刷屏
        if (!pending.isEmpty()) {
            log.debug("PEL 扫描 stream={} group={} pending={}", streamKey, group, pending.size());
        }
        return pending;
    }

    /**
     * XCLAIM：把指定消息重领给 consumer（投递次数 +1），返回带完整 body 的消息。
     *
     * <p>minIdleMs 由调用方传入，通常传 0（空闲过滤已在 XPENDING 侧完成）。
     * 注意 Lettuce 7 的 xclaim 用 {@link Consumer} 对象（内含 group + consumer 名）。
     */
    public List<StreamMessage<String, String>> xclaim(
            String streamKey, String group, String consumer, long minIdleMs, String... messageIds) {
        List<StreamMessage<String, String>> claimed =
                cdcSync().xclaim(streamKey, Consumer.from(group, consumer), minIdleMs, messageIds);
        log.debug("XCLAIM stream={} group={} ids={} 重领到={}",
                streamKey, group, messageIds.length, claimed.size());
        return claimed;
    }

    /**
     * XADD 字段式消息，返回消息 id。
     *
     * <p>毒消息转 DLQ 时用它把原始载荷连同诊断信息（ns/entity/投递次数/失败原因）
     * 一起写入 {@code {stream}:dlq}，供人工排查与重放。
     */
    public String xadd(String streamKey, Map<String, String> fields) {
        String id = cdcSync().xadd(streamKey, fields);
        log.debug("XADD stream={} id={} fields={}", streamKey, id, fields.keySet());
        return id;
    }

    /** XDEL 删除 stream 中的指定消息（DLQ 重放成功后清理用）。 */
    public long xdel(String streamKey, String... messageIds) {
        long deleted = cdcSync().xdel(streamKey, messageIds);
        log.debug("XDEL stream={} ids={} 删除={}", streamKey, messageIds.length, deleted);
        return deleted;
    }

    /** XACK（非阻塞连接版）：确认 group 中已处理的消息，把它移出 PEL。 */
    public long xack(String streamKey, String group, String messageId) {
        return cdcSync().xack(streamKey, group, messageId);
    }

    // ---- 运维命令（高可用/备份/演练，走主连接）----

    /** DBSIZE：当前逻辑库 key 总数。 */
    public long dbsize() {
        return sync().dbsize();
    }

    /** BGSAVE：触发后台 RDB 快照，返回状态串（如 Background saving started）。 */
    public String bgsave() {
        String status = sync().bgsave();
        log.debug("BGSAVE 触发结果={}", status);
        return status;
    }

    /** LASTSAVE：最后一次 RDB 落盘时间（Lettuce 返回 java.util.Date）。 */
    public Date lastSave() {
        return sync().lastsave();
    }

    /**
     * INFO section：解析为扁平 key→value（\r\n 行分隔，忽略注释与空行）。
     *
     * <p>section 取 server/clients/memory/persistence/stats/replication 等。
     * Redis 原生返回的是 {@code key:value} 文本行，这里统一解析成 Map，
     * 让上层取值时不必关心文本格式差异。
     */
    public Map<String, String> info(String section) {
        String raw = sync().info(section);
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (String line : raw.split("\r?\n")) {
            // 跳过空行与 "# Server" 这类分节注释行
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int idx = line.indexOf(':');
            if (idx > 0) {
                out.put(line.substring(0, idx), line.substring(idx + 1));
            }
        }
        log.debug("INFO {} 解析字段数={}", section, out.size());
        return out;
    }

    // ---- Query Engine（FT.*）命令（走主连接）----

    /**
     * FT 命令字。
     *
     * <p><b>为什么不用 Lettuce 原生 RediSearchCommands</b>：它是实验性 API，
     * 对 ON JSON + SORTBY + LIMIT 组合的参数覆盖有缺口，且会把 Lettuce 类型
     * 引入调用侧。也不直接用 {@link CommandType}：7.5.2 的枚举里有
     * FT_CREATE/FT_SEARCH/FT_DROPINDEX，但<b>没有 FT_INFO</b>（javap 反编译确认）。
     * 四个命令统一走自定义 ProtocolKeyword，行为一致、不依赖枚举覆盖度。
     */
    public enum FtCommand implements ProtocolKeyword {

        FT_CREATE, FT_SEARCH, FT_DROPINDEX, FT_INFO, FT_AGGREGATE;

        private final byte[] bytes;

        FtCommand() {
            // 枚举名的下划线还原成 RESP 的空格分隔点号：FT_CREATE -> "FT.CREATE"
            this.bytes = name().replace('_', '.').getBytes(StandardCharsets.US_ASCII);
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }
    }

    /**
     * 索引字段定义（业务语义，非 Lettuce 类型）。
     *
     * @param jsonPath JSON 文档内的路径（如 $.city）
     * @param alias    索引内别名（查询串里用 @alias 引用）
     * @param type     索引类型：TAG（等值，字符串/布尔）或 NUMERIC（等值/范围，数字/时间）
     * @param sortable 是否可排序；主键字段必须 sortable（SORTBY 稳定分页依赖它）
     */
    public record FtField(String jsonPath, String alias, String type, boolean sortable) {
    }

    /** FT.SEARCH 结果：total 为匹配总数（不受 LIMIT 影响），hits 为本页命中文档。 */
    public record FtSearchResult(long total, List<Hit> hits) {

        /**
         * 单条命中：key 为投影文档 key，json 为 JSON 文档体。
         * knnScore 为 KNN 查询的距离属性 {@code __vec_score}（distance = 1 - 余弦），
         * 仅 KNN 查询携带；普通查询为 null。
         */
        public record Hit(String key, String json, Double knnScore) {

            /** 兼容构造：非 KNN 查询不携带分数。 */
            public Hit(String key, String json) {
                this(key, json, null);
            }
        }
    }

    /**
     * FT.INFO 解析结果：索引文档数 + 别名→索引类型映射（幂等比对用）+ 回填进度。
     *
     * @param numDocs         已索引文档数
     * @param fieldTypes      别名 → 索引类型
     * @param fieldSortable   别名 → 是否 SORTABLE
     * @param percentIndexed  回填完成度，0~1 小数（1 = 100% 完成）。注意这是 Redis 的
     *                        语义，不是 RediSearch 的 0-100 整数
     * @param indexing        引擎是否仍在后台回填（1 = 进行中）
     */
    public record FtIndexInfo(long numDocs, Map<String, String> fieldTypes,
                              Map<String, Boolean> fieldSortable,
                              double percentIndexed, boolean indexing) {

        /** 回填是否已完成（可安全走索引路径）。 */
        public boolean backfillComplete() {
            return !indexing && percentIndexed >= 1.0;
        }
    }

    /**
     * FT.CREATE：为 JSON 文档集合创建二级索引。
     *
     * <p>固定 {@code ON JSON PREFIX 1 docPrefix}——索引覆盖 docPrefix 下的全部 JSON 文档。
     * 已存在同名索引会报错，幂等判断（先 FT.INFO 比对）由 {@link EntityIndexManager} 负责，
     * 本方法只负责忠实发命令。
     */
    public void ftCreate(String index, String docPrefix, List<FtField> fields) {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                .add(index)
                .add("ON").add("JSON")
                .add("PREFIX").add("1").add(docPrefix)
                .add("SCHEMA");
        for (FtField f : fields) {
            args.add(f.jsonPath()).add("AS").add(f.alias()).add(f.type());
            if (f.sortable()) {
                args.add("SORTABLE");
            }
        }
        sync().dispatch(FtCommand.FT_CREATE, new StatusOutput<>(StringCodec.UTF8), args);
        log.info("FT.CREATE index={} prefix={} 字段={}",
                index, docPrefix, fields.stream().map(FtField::alias).toList());
    }

    /**
     * FT.SEARCH：索引查询 + 分页下推。
     *
     * <p>固定 {@code DIALECT 2}（redis-cli 验证结论）：TAG 查询的<b>带引号值语法</b>
     * {@code @city:{"上海"}} 在方言 1 下直接语法报错、方言 2 下正常——
     * 而"永远引号包值"是防查询串注入的前提，因此必须钉死方言 2。
     * {@code SORTBY sortField ASC} 保证深翻页结果稳定（无排序的分页在
     * 索引内部顺序变化时会出现跨页重复/漏行）。
     *
     * <p>返回的 total 是<b>匹配总数</b>而非本页条数——分页下推后调用方无需
     * 再发一次 COUNT 查询。打 debug：这是过滤查询主路径的耗时主体，
     * 排查"查询为什么慢"时的核心观测点。
     *
     * @param index     索引名（{@link KeyStrategy#indexKey} 生成）
     * @param query     查询串（由 {@link SearchQueryTranslator} 生成，已含转义）
     * @param offset    起始偏移（(page-1)*size）
     * @param count     本页条数
     * @param sortField 排序字段别名（主键），必须建为 SORTABLE
     */
    public FtSearchResult ftSearch(
            String index, String query, long offset, long count, String sortField) {
        return ftSearch(index, query, offset, count, sortField, false);
    }

    /**
     * 索引查询（过滤 + 排序方向 + 分页下推）。
     *
     * @param sortField 排序字段别名，必须建为 SORTABLE
     * @param desc      true = DESC（降序，如「金额最高的前 N 条」）；false = ASC
     */
    public FtSearchResult ftSearch(
            String index, String query, long offset, long count,
            String sortField, boolean desc) {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                .add(index)
                .add(query)
                .add("DIALECT").add("2")
                .add("SORTBY").add(sortField).add(desc ? "DESC" : "ASC")
                .add("LIMIT").add(Long.toString(offset)).add(Long.toString(count));
        List<Object> reply = sync().dispatch(
                FtCommand.FT_SEARCH, new ArrayOutput<>(StringCodec.UTF8), args);
        return parseFtSearch(index, query, reply);
    }

    /**
     * FT.SEARCH count 复读（{@code NOCONTENT LIMIT 0 0}）：只取匹配总数，不取文档。
     *
     * <p>护栏用： findByIndex 拿到分页结果后用它复读 total，两次读数一致才采信——
     * 引擎后台重扫期间单次 FT.SEARCH 的 total 可能是「已重扫部分」的假计数
     * （可能像 2518→28518→10818→50000 这样跳变）。
     * NOCONTENT 让 Redis 不回传文档载荷，单次往返微秒级，护栏开销可忽略。
     */
    public long ftSearchCount(String index, String query) {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                .add(index)
                .add(query)
                .add("DIALECT").add("2")
                .add("NOCONTENT")
                .add("LIMIT").add("0").add("0");
        List<Object> reply = sync().dispatch(
                FtCommand.FT_SEARCH, new ArrayOutput<>(StringCodec.UTF8), args);
        return parseSearchTotal(index, reply);
    }

    /**
     * 从 FT.SEARCH 应答抽取 total（兼容 RESP2 老格式与 RESP3 map 新格式）。
     * 与 {@link #parseFtSearch} 同源的判定规则：老格式首元素是数字（total），
     * 新格式是扁平 map，取 {@code total_results} 键。
     */
    private long parseSearchTotal(String index, List<Object> reply) {
        if (reply == null || reply.isEmpty()) {
            return 0;
        }
        if (reply.get(0) instanceof Number n) {
            return n.longValue();
        }
        if (reply.get(0) == null) {
            // NOCONTENT + LIMIT 0 0 的老格式是 [total]，total 不会是 null；防御一下
            return 0;
        }
        // 新格式：扁平 map 取 total_results
        for (int i = 0; i + 1 < reply.size(); i += 2) {
            if ("total_results".equals(String.valueOf(reply.get(i)))) {
                Object value = reply.get(i + 1);
                if (value instanceof Number n) {
                    return n.longValue();
                }
                return Long.parseLong(String.valueOf(value));
            }
        }
        log.debug("FT.SEARCH count 应答形态未识别 index={} 首元素类型={} 前6元素={}",
                index, reply.get(0).getClass().getSimpleName(),
                reply.subList(0, Math.min(6, reply.size())));
        return 0;
    }

    /**
     * FT.AGGREGATE 服务端聚合。
     *
     * <p>命令形态（全部走别名 @field，别名与 FT.SEARCH 一致）：
     * <pre>
     * FT.AGGREGATE idx query DIALECT 2
     *   [GROUPBY n @f1 @f2 ... | GROUPBY 0]
     *   REDUCE op nargs [@field] AS alias ...
     *   [SORTBY 2 @alias ASC|DESC]
     *   LIMIT 0 limit
     * </pre>
     *
     * <p><b>RESP2 应答形态</b>：{@code [总组数, [k1, v1, k2, v2, ...], [..], ...]}——
     * 首元素是组总数（不受 LIMIT 影响），其后每组是一个扁平键值对数组。
     * 用 {@link ArrayOutput} 收（与 FT.SEARCH 同源），键值还原为字符串，
     * 数值转换由上层做（它知道哪个别名是数值归约）。
     *
     * @param index      索引名
     * @param query      过滤查询串（{@link SearchQueryTranslator} 生成，"*" = 全量）
     * @param groupBy    分组字段别名；空 = 全局单组（GROUPBY 0）
     * @param reduces    归约子句（op/field/alias 已解析）
     * @param sortBy     排序别名（metric 别名或分组字段）；null = 不排序
     * @param desc       排序方向
     * @param limit      返回组数
     */
    public FtAggregateResult ftAggregate(String index, String query,
                                         List<String> groupBy, List<AggregateReduce> reduces,
                                         String sortBy, boolean desc, int limit) {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                .add(index)
                .add(query);
        if (groupBy.isEmpty()) {
            args.add("GROUPBY").add("0");
        } else {
            args.add("GROUPBY").add(Integer.toString(groupBy.size()));
            for (String f : groupBy) {
                args.add("@" + f);
            }
        }
        for (AggregateReduce r : reduces) {
            args.add("REDUCE").add(r.op().toUpperCase());
            if ("COUNT".equals(r.op().toUpperCase())) {
                args.add("0");
            } else {
                args.add("1").add("@" + r.field());
            }
            args.add("AS").add(r.alias());
        }
        if (sortBy != null) {
            args.add("SORTBY").add("2").add("@" + sortBy).add(desc ? "DESC" : "ASC");
        }
        args.add("LIMIT").add("0").add(Integer.toString(limit));
        // DIALECT 必须是最后一个参数（trailing option）：放在子句中间会触发
        // 引擎异常——rows 为空、total 跳变（2957→30412/34212）且带
        // 「Timeout limit was reached」warning（AGGREGATE 与
        // SEARCH 的参数解析器对子句位置的要求不同）
        args.add("DIALECT").add("2");
        List<Object> reply;
        FtAggregateResult result = null;
        // 引擎超时兜底：TIMEOUT=500ms 下，大基数 GROUPBY+SORTBY
        // 撞上瞬时 CPU 抖动会返回「空 rows + 跳变 total + Timeout limit was reached warning」，
        // 且失败窗口可连续出现（单次重跑不能保证恢复）——
        // 最多 3 次尝试 + 微退避（200/400ms）。
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (attempt > 1) {
                try {
                    Thread.sleep(200L * (attempt - 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            reply = sync().dispatch(
                    FtCommand.FT_AGGREGATE, new ArrayOutput<>(StringCodec.UTF8), args);
            result = parseFtAggregate(index, reply);
            // 成功（有行）/ 真无数据（total=0 = 过滤无匹配，合法空）/ 单组请求（limit<=1）
            // 三种情况不再重试
            if (!result.rows().isEmpty() || result.totalGroups() == 0 || limit <= 1) {
                break;
            }
            log.warn("FT.AGGREGATE 空结果（疑似引擎超时窗口）第 {}/3 次 index={} total={} limit={}",
                    attempt, index, result.totalGroups(), limit);
        }
        // 显式防御性判空（不用 assert——生产环境默认 -da 不生效，等于没判）：
        // 循环体正常至少执行一次，理论上 result 已赋值；此分支只兜「未预期路径」
        if (result == null) {
            throw new IrisException(ErrorCode.INVALID_QUERY, "聚合结果缺失（未预期路径），请重试或换查询标准");
        }
        // 3 次耗尽仍空但有组数 = 引擎超时截断的假结果（total 还在跳变，不可信）：
        // 抛业务异常让上层（Agent）读到「这是超时、可重试」，而不是把
        // 「total=假值 + rows=[]」当真数据直通给模型让其误判「信息不足」
        if (result.rows().isEmpty() && result.totalGroups() > 0 && limit > 1) {
            // 文案明确「别原样重试」：RepeatGuard 同参第 2 次即拦，教模型重试只会浪费一轮
            // （模型层重复同参重试会被护栏拦截，徒增困惑）。
            // 瞬时抖动已由本方法内 3 次重试+退避覆盖，模型层应直接换标准。
            throw new IrisException(ErrorCode.INVALID_QUERY,
                    "聚合引擎超时：分组基数过大或撞上资源抖动，服务端已自动重试仍未完成；"
                            + "请勿原样重试（会被重复护栏拦截），直接换更低基数的分组字段，"
                            + "或增加过滤条件缩小范围后再聚合");
        }
        return result;
    }

    /** 聚合归约子句（业务语义）：op ∈ count|sum|avg|min|max；count 不带 field。 */
    public record AggregateReduce(String op, String field, String alias) {
    }

    /** FT.AGGREGATE 结果：totalGroups 为分组总数，rows 为扁平键值对还原的组行。 */
    public record FtAggregateResult(long totalGroups, List<Map<String, String>> rows) {
    }

    /**
     * 解析 FT.AGGREGATE 应答（双协议，与 {@link #parseSearchTotal} 同源判定规则）。
     *
     * <p><b>RESP2</b>：{@code [总组数, [k1,v1,...], ...]}——首元素是数字（组总数），
     * 其后每组是扁平键值对数组。
     *
     * <p><b>RESP3（Lettuce 默认）</b>：顶层扁平 map（ArrayOutput 把 map 摊平成
     * 交替 key/value 的 List）——{@code format/results/total_results/warning}；
     * 每行是摊平的行 map：{@code extra_attributes -> [k,v,...]} + {@code values -> [...]}。
     * redis-cli -3 验证：GROUPBY 字段与归约别名都在 extra_attributes 里。
     */
    private FtAggregateResult parseFtAggregate(String index, List<Object> reply) {
        if (reply == null || reply.isEmpty()) {
            return new FtAggregateResult(0, List.of());
        }
        // ---- RESP2 老格式 ----
        if (reply.get(0) instanceof Number n) {
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 1; i < reply.size(); i++) {
                if (reply.get(i) instanceof List<?> raw) {
                    rows.add(pairsToMap(raw));
                }
            }
            log.debug("FT.AGGREGATE 完成(RESP2) index={} 组总数={} 返回组数={}", index, n, rows.size());
            return new FtAggregateResult(n.longValue(), rows);
        }
        // ---- RESP3 新格式 ----
        long total = 0;
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i + 1 < reply.size(); i += 2) {
            String key = String.valueOf(reply.get(i));
            Object value = reply.get(i + 1);
            if ("total_results".equals(key) && value instanceof Number num) {
                total = num.longValue();
            } else if ("results".equals(key) && value instanceof List<?> list) {
                for (Object r : list) {
                    if (r instanceof List<?> rowFlat) {
                        rows.add(aggregateRowToMap(rowFlat));
                    }
                }
            }
        }
        if (total == 0 && rows.isEmpty()) {
            log.debug("FT.AGGREGATE 应答形态未识别 index={} 前6元素={}",
                    index, reply.subList(0, Math.min(6, reply.size())));
        }
        log.debug("FT.AGGREGATE 完成 index={} 组总数={} 返回组数={}", index, total, rows.size());
        return new FtAggregateResult(total, rows);
    }

    /** RESP3 聚合行：摊平行 map，取 extra_attributes（分组键+归约值）与直接键值对。 */
    private Map<String, String> aggregateRowToMap(List<?> rowFlat) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < rowFlat.size(); i += 2) {
            String key = String.valueOf(rowFlat.get(i));
            Object v = rowFlat.get(i + 1);
            if ("extra_attributes".equals(key) && v instanceof List<?> pairs) {
                row.putAll(pairsToMap(pairs));
            } else if ("values".equals(key)) {
                // VALUES 键下的内容按扁平对尝试解析（部分引擎形态），空列表自然跳过
                if (v instanceof List<?> pairs && pairs.size() % 2 == 0) {
                    row.putAll(pairsToMap(pairs));
                }
            } else if (v != null && !(v instanceof List<?>)) {
                row.put(key, String.valueOf(v));
            }
        }
        return row;
    }

    /** 扁平 [k,v,...] 序列还原为有序 map；奇数尾巴/空键防御性跳过。 */
    private Map<String, String> pairsToMap(List<?> flat) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int j = 0; j + 1 < flat.size(); j += 2) {
            if (flat.get(j) != null) {
                m.put(String.valueOf(flat.get(j)), String.valueOf(flat.get(j + 1)));
            }
        }
        return m;
    }

    /**
     * HSET：向 HASH 写入一个二进制值（field -> raw bytes）。
     *
     * <p>长期记忆向量以 float32 小端序二进制存进 HASH
     * （{@code vec} 字段），供 HNSW 向量索引消费。Lettuce 的类型化 hset API
     * 绑定 String 编码器装不下二进制，这里走自定义 dispatch 直接塞 bulk bytes。
     * 不打日志（保存记忆的伴生写，高频）。
     */
    public void hsetBinary(String key, String field, byte[] value) {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                .add(key).add(field).add(value);
        sync().dispatch(CommandType.HSET, new IntegerOutput<>(StringCodec.UTF8), args);
    }

    /**
     * INCR：原子自增并返回新值（数据版本守卫）。
     *
     * <p><b>输出必须用 IntegerOutput</b>：INCR 回复是整数（:N），与 HSET 同理
     * 不能用 StatusOutput 接（参见 hsetBinary 的教训）。
     * key 不存在时从 0 起增（Redis 原生语义）。
     */
    public long incr(String key) {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8).add(key);
        Long v = sync().dispatch(CommandType.INCR, new IntegerOutput<>(StringCodec.UTF8), args);
        return v == null ? 0 : v;
    }

    /**
     * HGET：读取 hash 字段的<b>原始字节</b>（与 {@link #hsetBinary} 对称的读通道）。
     *
     * <p>为什么不用 {@code hget}：普通 hget 走 String 编解码，二进制值（float32
     * 向量）会被 UTF-8 解码打碎成乱码且不可逆——控制台"向量摘要"必须拿原始字节。
     *
     * @return 字段值原始字节；字段不存在返回 null
     */
    public byte[] hgetBinary(String key, String field) {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                .add(key).add(field);
        return sync().dispatch(CommandType.HGET,
                new ByteArrayOutput<>(StringCodec.UTF8), args);
    }

    /**
     * HSET：向 HASH 写入一个字符串值（field -> value）。
     *
     * <p>工作记忆语义索引引入：向量 HASH 里除 {@code vec} 二进制向量外，
     * 还要写 {@code session} TAG 字段供按会话过滤。
     * <b>输出必须用 IntegerOutput</b>：HSET 回复是整数（:1），StatusOutput
     * 不支持 set(long) 会抛异常——若误用 StatusOutput，命令在服务端
     * 已执行成功但客户端解码报错，会被调用方 catch 吞掉（静默丢写）。
     * 不打日志（伴生写，高频）。
     */
    public void hset(String key, String field, String value) {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                .add(key).add(field).add(value);
        sync().dispatch(CommandType.HSET, new IntegerOutput<>(StringCodec.UTF8), args);
    }

    /**
     * HGET：读取 HASH 单个字段。
     *
     * <p>动态 agent key 按指纹取单条。管理面低频命令，打 debug。
     *
     * @return 字段值；不存在返回 null
     */
    public String hget(String key, String field) {
        return sync().hget(key, field);
    }

    /**
     * HGETALL：读取整个 HASH（field -> value）。
     *
     * <p>动态 agent key 注册表全量刷新。条目量 = agent key 数，
     * 量级小，全量拉取成本可忽略；刷新是低频操作（默认 30s 一次 + 管理写后即时）。
     */
    public Map<String, String> hgetAll(String key) {
        return sync().hgetall(key);
    }

    /**
     * HDEL：删除 HASH 字段。
     *
     * @return 实际删除的字段数（0 = 字段不存在）
     */
    public long hdel(String key, String... fields) {
        return sync().hdel(key, fields);
    }

    /**
     * SET key value NX PX &lt;millis&gt;：不存在才写入的短锁（工作记忆摘要互斥用）。
     *
     * @return true=拿到锁；false=锁被他人持有
     */
    public boolean setNxPx(String key, String value, long pxMillis) {
        String result = sync().set(key, value, SetArgs.Builder.nx().px(pxMillis));
        return "OK".equals(result);
    }

    /**
     * SET key value NX：不存在才写入，<b>无 TTL</b>。
     *
     * <p>与 {@link #setNxPx} 的差异：不设过期。用于「一次性标记」类场景
     * （如长期记忆索引的冷启动回填标记）——标记必须与索引共存亡，带 TTL 的
     * 占位会在过期后误触发第二次全量回填。
     *
     * @return true=写入成功（本调用占位）；false=key 已存在
     */
    public boolean setIfAbsent(String key, String value) {
        String result = sync().set(key, value, SetArgs.Builder.nx());
        return "OK".equals(result);
    }

    /**
     * 剥掉 JSON.GET 带 path 调用的返回包裹。
     *
     * <p>RedisJSON 的 <b>path 查询结果恒为数组形态</b>：根 path {@code $} 返回
     * {@code [doc]}、字段 path 返回 {@code [value]}（无 path 调用才是裸文档）。
     * {@link #jsonGetPathBatch} 按 path 批量回捞，调用方若按裸文档解析会把
     * {@code [{...}]} 整体当成解析失败。本方法把「单元素数组」还原为元素本体；
     * 非数组形态原样返回（防御未来 Lettuce/RedisJSON 行为变化）。
     */
    public static String unwrapJsonPathResult(String json) {
        if (json == null || json.length() < 2 || json.charAt(0) != '[') {
            return json;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = JSON_MAPPER.readTree(json);
            if (node.isArray() && node.size() == 1) {
                return node.get(0).toString();
            }
        } catch (Exception ignored) {
            // 解析失败按原样返回——由调用方的 JSON 解析步骤抛出真实错误
        }
        return json;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** DEL key 后返回被删的 key（供验证断言用，业务路径请用 {@link #del}）。 */
    public boolean delAndReport(String key) {
        return sync().del(key) > 0;
    }

    /**
     * EVAL 执行 Lua 脚本（工作记忆原子追加用）。
     *
     * <p>为什么不走事务/ WATCH：JSON 文档的"读-改-写"在 WATCH 重试语义下
     * 要把整个文档往返传输；Lua 在服务端一次完成判型迁移+追加，既原子又省 RTT。
     * 返回值忽略——调用方只关心执行成功（失败会抛 RedisException）。
     * <b>输出类型必须用 INTEGER</b>：脚本 return 整数，VALUE 输出不支持整数回复
     * （注意：ValueOutput.set(long) 抛 UnsupportedOperationException，
     * 导致"脚本已执行成功但客户端报错"的假失败）。
     */
    public void eval(String script, List<String> keys, String... values) {
        sync().eval(script, ScriptOutputType.INTEGER,
                keys.toArray(new String[0]), values);
    }

    /**
     * FT.CREATE：为 HASH 文档集合创建<b>向量索引</b>（长期记忆 KNN 检索用）。
     *
     * <p>固定 {@code ON HASH PREFIX 1 docPrefix}，向量字段：
     * {@code vec VECTOR HNSW 6 TYPE FLOAT32 DIM <dim> DISTANCE_METRIC COSINE}。
     * <ul>
     *   <li><b>HNSW</b>：近似最近邻图索引，KNN 查询 O(logN)，远优于暴力 SCAN；
     *       6 是 M 参数（每节点最大出边，Redis 默认值）；</li>
     *   <li><b>COSINE</b>：余弦距离——与 Embedder 的 L2 归一化约定配套
     *       （归一化后余弦=点积，值域 [0,1] 直观可解释）。</li>
     * </ul>
     * <p><b>tagFields 可变参数</b>：额外声明 TAG 字段（工作记忆索引的 session
     * 过滤段）——KNN 预过滤（{@code @session:{sid}=>[KNN ...]}）要求过滤字段
     * 必须在索引 SCHEMA 里声明，否则查询直接报错。
     * 幂等判断（索引是否已存在）由调用方负责，本方法只忠实发命令。
     */
    public void ftCreateVector(String index, String docPrefix, String vectorAlias, int dim,
                               String... tagFields) {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                .add(index)
                .add("ON").add("HASH")
                .add("PREFIX").add("1").add(docPrefix)
                .add("SCHEMA");
        for (String tagField : tagFields) {
            args.add(tagField).add("TAG");
        }
        args.add(vectorAlias).add("VECTOR").add("HNSW").add("6")
                .add("TYPE").add("FLOAT32")
                .add("DIM").add(Integer.toString(dim))
                .add("DISTANCE_METRIC").add("COSINE");
        sync().dispatch(FtCommand.FT_CREATE, new StatusOutput<>(StringCodec.UTF8), args);
        log.info("FT.CREATE(VECTOR) index={} prefix={} dim={} tags={}",
                index, docPrefix, dim, tagFields.length);
    }

    /**
     * FT.SEARCH KNN：向量近邻查询（长期记忆语义检索用），返回按相似度降序的文档 key。
     *
     * <p>查询串形如 {@code *=>[KNN 10 @vec $q]}：KNN 子句按向量距离取 top-k。
     * 查询向量经 {@code PARAMS 2 q <bytes>} 传入——二进制参数无法拼进查询串，
     * 必须走 PARAMS 通道。
     *
     * <p><b>已知陷阱（Redis 8.10.1 验证）</b>：KNN 结果<b>不自动按距离排序</b>——
     * 返回的 {@code __vec_score}（distance = 1 - 余弦）数值完全正确，但文档顺序
     * 是插入序而非距离序。必须显式 {@code SORTBY __vec_score ASC} 才能得到
     * "最相似在前"。score 属性名规则：{@code __<向量别名>_score}。
     * <p>固定 DIALECT 2（与 {@link #ftSearch} 同理由）。
     * 复用 {@link #parseFtSearch} 双格式解析：我们只要命中文档 key（id），
     * HASH 文档没有 "$" JSON 体，Hit.json 为 null 属预期。
     */
    /** KNN 命中：key + 距离（distance = 1 - 余弦，服务端算好，免去客户端重嵌入）。 */
    public record ScoredKnn(String key, double distance) {
    }

    /** KNN 检索（仅返回 key 列表，距离丢弃——供不需要分数的场景）。 */
    public List<String> ftSearchKnn(String index, String query, byte[] queryVec, int knn) {
        return ftSearchKnnScored(index, query, queryVec, knn).stream()
                .map(ScoredKnn::key)
                .toList();
    }

    /** KNN 检索（带服务端距离）：调用方直接 1 - distance 得余弦相似度。 */
    public List<ScoredKnn> ftSearchKnnScored(String index, String query, byte[] queryVec, int knn) {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                .add(index)
                .add(query)
                .add("SORTBY").add("__vec_score").add("ASC")
                .add("PARAMS").add("2").add("q").add(queryVec)
                .add("DIALECT").add("2");
        List<Object> reply = sync().dispatch(
                FtCommand.FT_SEARCH, new ArrayOutput<>(StringCodec.UTF8), args);
        FtSearchResult result = parseFtSearch(index, query, reply);
        List<ScoredKnn> scored = new ArrayList<>();
        for (FtSearchResult.Hit hit : result.hits()) {
            // 距离缺失（属性未回/解析失败）按 NaN 透传，由调用方决定回落策略
            scored.add(new ScoredKnn(hit.key(), hit.knnScore() != null ? hit.knnScore() : Double.NaN));
        }
        log.debug("FT.SEARCH(KNN) index={} knn={} 命中={}", index, knn, scored.size());
        return scored;
    }

    /**
     * FT.SEARCH 词法排名查询（RAG 词法通道专用）：不带 SORTBY，按引擎默认序返回——
     * RediSearch 对文本查询的默认排序就是 BM25 相关度降序，RRF 只需要排名不需要分数，
     * 免去 WITHSCORES 的 RESP2/RESP3 双格式分数解析（分数标准也不进业务语义）。
     *
     * <p>查询串由调用方拼装（{@code @lex:(a|b|c)}，TAG 过滤段可叠加），
     * 词项经 {@code LexTerms.orQuery} 生成——纯字母/数字词项，无转义负担。
     * 固定 DIALECT 2（与 {@link #ftSearch} 同理由）。
     */
    public List<String> ftSearchRank(String index, String query, int limit) {
        CommandArgs<String, String> args = new CommandArgs<>(StringCodec.UTF8)
                .add(index)
                .add(query)
                .add("LIMIT").add("0").add(Integer.toString(limit))
                .add("DIALECT").add("2");
        List<Object> reply = sync().dispatch(
                FtCommand.FT_SEARCH, new ArrayOutput<>(StringCodec.UTF8), args);
        FtSearchResult result = parseFtSearch(index, query, reply);
        List<String> keys = result.hits().stream().map(FtSearchResult.Hit::key).toList();
        log.debug("FT.SEARCH(词法) index={} limit={} 命中={}", index, limit, keys.size());
        return keys;
    }

    /**
     * 解析 FT.SEARCH 应答，<b>兼容两种格式</b>。
     *
     * <p><b>老格式（RESP2 / 传统 Stack）</b>：扁平数组
     * {@code [total, key1, ["$", json], key2, ...]}，首元素是匹配总数。
     *
     * <p><b>新格式（RESP3，Redis 8）</b>：扁平 map
     * {@code [attributes, [], format, STRING, results, [[id, key, extra_attributes,
     * [id, pk, $, json], values, []], ...], total_results, N, warning, []]}。
     * 注意：Lettuce 默认协商 RESP3，Redis 8 对 RESP3 客户端返回 map 形态——
     * 用 redis-cli（默认 RESP2）裸测看到的永远是老格式，会掩盖这个差异。
     *
     * <p>两种格式按首元素类型区分：老格式首元素是数字（total），新格式首元素是
     * 字符串 {@code attributes}。
     */
    private FtSearchResult parseFtSearch(String index, String query, List<Object> reply) {
        if (reply == null || reply.isEmpty()) {
            return new FtSearchResult(0, List.of());
        }
        if (reply.get(0) instanceof Number || reply.get(0) == null) {
            return parseLegacySearchReply(index, query, reply);
        }
        return parseModernSearchReply(index, query, reply);
    }

    /** 老格式解析：{@code [total, key, ["$", json], ...]}。 */
    private FtSearchResult parseLegacySearchReply(
            String index, String query, List<Object> reply) {
        long total;
        Object first = reply.get(0);
        if (first instanceof Number n) {
            total = n.longValue();
        } else {
            try {
                total = Long.parseLong(String.valueOf(first));
            } catch (NumberFormatException e) {
                // 两种已知格式都不匹配：打出真实形态，避免静默降级掩盖协议差异
                log.debug("FT.SEARCH 应答形态未识别 index={} 首元素类型={} 前6元素={}",
                        index, first == null ? "null" : first.getClass().getSimpleName(),
                        reply.subList(0, Math.min(6, reply.size())));
                throw e;
            }
        }
        List<FtSearchResult.Hit> hits = new ArrayList<>();
        // 从第 1 位起两两一组：key + 文档字段数组
        for (int i = 1; i < reply.size(); i += 2) {
            String key = String.valueOf(reply.get(i));
            String json = null;
            Double knnScore = null;
            if (i + 1 < reply.size() && reply.get(i + 1) instanceof List<?> doc) {
                for (int j = 0; j + 1 < doc.size(); j += 2) {
                    String field = String.valueOf(doc.get(j));
                    if ("$".equals(field)) {
                        json = String.valueOf(doc.get(j + 1));
                    } else if ("__vec_score".equals(field)) {
                        knnScore = parseScore(doc.get(j + 1));
                    }
                }
            }
            hits.add(new FtSearchResult.Hit(key, json, knnScore));
        }
        log.debug("FT.SEARCH(legacy) index={} query='{}' total={} 返回={}",
                index, query, total, hits.size());
        return new FtSearchResult(total, hits);
    }

    /** 新格式解析（RESP3 map）：{@code [attributes, ..., results, [...], total_results, N, ...]}。 */
    private FtSearchResult parseModernSearchReply(String index, String query, List<Object> reply) {
        long total = 0;
        List<FtSearchResult.Hit> hits = new ArrayList<>();
        // 顶层扁平 map 遍历：只关心 results 与 total_results 两个键
        for (int i = 0; i + 1 < reply.size(); i += 2) {
            String key = String.valueOf(reply.get(i));
            Object value = reply.get(i + 1);
            if ("total_results".equals(key)) {
                if (value instanceof Number n) {
                    total = n.longValue();
                } else {
                    total = Long.parseLong(String.valueOf(value));
                }
            } else if ("results".equals(key) && value instanceof List<?> results) {
                for (Object r : results) {
                    if (r instanceof List<?> result) {
                        FtSearchResult.Hit hit = parseModernResultEntry(result);
                        if (hit != null) {
                            hits.add(hit);
                        }
                    }
                }
            }
            // attributes（排序聚合属性）/ format / warning 等键与本查询无关，跳过
        }
        log.debug("FT.SEARCH(resp3) index={} query='{}' total={} 返回={}",
                index, query, total, hits.size());
        return new FtSearchResult(total, hits);
    }

    /**
     * 解析新格式单个 result：{@code [id, key, extra_attributes, [id, pk, $, json], values, []]}。
     *
     * <p>注意 {@code id} 出现在两处：result 顶层的 id 是文档 key，
     * extra_attributes 内部的 id 是主键字段值——按层级区分，互不混淆。
     */
    private FtSearchResult.Hit parseModernResultEntry(List<?> result) {
        String key = null;
        String json = null;
        Double knnScore = null;
        for (int i = 0; i + 1 < result.size(); i += 2) {
            String field = String.valueOf(result.get(i));
            Object value = result.get(i + 1);
            if ("id".equals(field)) {
                key = String.valueOf(value);
            } else if ("extra_attributes".equals(field) && value instanceof List<?> attrs) {
                for (int j = 0; j + 1 < attrs.size(); j += 2) {
                    String attr = String.valueOf(attrs.get(j));
                    if ("$".equals(attr)) {
                        json = String.valueOf(attrs.get(j + 1));
                    } else if ("__vec_score".equals(attr)) {
                        knnScore = parseScore(attrs.get(j + 1));
                    }
                }
            }
            // values 键（仅 RETURN 指定字段时填充）与文档 JSON 二选一，本实现固定取 $ 全文档
        }
        return new FtSearchResult.Hit(key, json, knnScore);
    }

    /** KNN 距离属性解析：Redis 可能回数字或字符串，异常按缺失处理（调用方回落本地余弦）。 */
    private static Double parseScore(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * FT.DROPINDEX：删除索引定义，不动数据（不带 DD 参数，JSON 文档保留）。
     *
     * <p>索引不存在返回 false 而不是抛异常——幂等重建流程里
     * "先删旧的再建新的"是正常分支，不该用异常表达。
     */
    public boolean ftDropindex(String index) {
        try {
            sync().dispatch(FtCommand.FT_DROPINDEX, new StatusOutput<>(StringCodec.UTF8),
                    new CommandArgs<>(StringCodec.UTF8).add(index));
            log.info("FT.DROPINDEX index={}", index);
            return true;
        } catch (RedisException e) {
            // 索引不存在属于幂等重建的正常分支。不同 Redis 版本的报错措辞不同：
            // 老版本是 "Unknown Index name"，8.10.1 是 "SEARCH_INDEX_NOT_FOUND Index not found"，
            // 统一按"消息里含 not found / unknown index"识别（小写比较），避免措辞漂移导致误判。
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("not found") || msg.contains("unknown index")) {
                return false;
            }
            throw e;
        }
    }

    /**
     * FT.INFO：读取索引元信息（文档数 + 字段定义），供幂等比对。
     *
     * @throws RedisException 索引不存在时（Redis 报 Unknown Index name），
     *                        由调用方决定如何处理
     */
    public FtIndexInfo ftInfo(String index) {
        List<Object> reply = sync().dispatch(FtCommand.FT_INFO,
                new ArrayOutput<>(StringCodec.UTF8),
                new CommandArgs<>(StringCodec.UTF8).add(index));
        return parseFtInfo(reply);
    }

    /**
     * 解析 FT.INFO 应答。
     *
     * <p>RESP 形态：扁平 {@code [名, 值, 名, 值, ...]}，其中 attributes 的值是
     * 嵌套数组，每个元素是一个字段的键值/标志混合列表
     * {@code [identifier, $.city, attribute, city, type, TAG, flags, [SORTABLE, UNF]]}
     * ——flags 是键 + 嵌套标志列表（Lettuce 运行时形态），不是扁平 token。
     * 这里抽幂等比对需要的 num_docs、别名→类型映射、别名→SORTABLE 映射
     * （排序下推要求索引字段带 SORTABLE，比对缺失会触发自动重建补齐），
     * 以及回填进度 percent_indexed/indexing（判断索引是否可安全查询）。
     */
    private FtIndexInfo parseFtInfo(List<Object> reply) {
        long numDocs = 0;
        double percentIndexed = 1.0;
        boolean indexing = false;
        Map<String, String> fieldTypes = new LinkedHashMap<>();
        Map<String, Boolean> fieldSortable = new LinkedHashMap<>();
        for (int i = 0; i + 1 < reply.size(); i += 2) {
            String name = String.valueOf(reply.get(i));
            Object value = reply.get(i + 1);
            if ("num_docs".equals(name)) {
                // 与 FT.SEARCH 的 total 同理：整数可能解码为 Long 也可能为 String，都接住
                if (value instanceof Number n) {
                    numDocs = n.longValue();
                } else {
                    try {
                        numDocs = Long.parseLong(String.valueOf(value));
                    } catch (NumberFormatException ignored) {
                        // 异常形态的 num_docs 不影响幂等比对（比对的是字段定义）
                    }
                }
            }
            if ("percent_indexed".equals(name)) {
                try {
                    percentIndexed = Double.parseDouble(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                    // 解析不出按已完成处理（老版本/异常形态不阻断查询）
                }
            }
            if ("indexing".equals(name)) {
                indexing = "1".equals(String.valueOf(value)) || "true".equalsIgnoreCase(String.valueOf(value));
            }
            if ("attributes".equals(name) && value instanceof List<?> attrs) {
                for (Object a : attrs) {
                    if (a instanceof List<?> attr) {
                        String alias = null;
                        String type = null;
                        boolean sortable = false;
                        // 逐 token 扫描而非固定 j+=2 键值配对：Lettuce 运行时形态为
                        // [identifier, $.id, attribute, id, type, NUMERIC, flags, [SORTABLE, UNF]]，
                        // flags 是键 + 嵌套列表，裸 token 扫描/固定配对都接不住。
                        // 键名大小写混用（identifier 小写、SORTABLE 大写），统一小写比较。
                        for (int j = 0; j < attr.size(); j++) {
                            String k = String.valueOf(attr.get(j)).toLowerCase(java.util.Locale.ROOT);
                            switch (k) {
                                case "attribute" -> {
                                    if (j + 1 < attr.size()) alias = String.valueOf(attr.get(++j));
                                }
                                case "type" -> {
                                    if (j + 1 < attr.size()) type = String.valueOf(attr.get(++j));
                                }
                                // 主形态：flags 键 + 嵌套标志列表（SORTABLE/UNF/...）
                                case "flags" -> {
                                    if (j + 1 < attr.size() && attr.get(j + 1) instanceof List<?> flags) {
                                        for (Object f : flags) {
                                            if ("sortable".equalsIgnoreCase(String.valueOf(f))) {
                                                sortable = true;
                                            }
                                        }
                                        j++;
                                    }
                                }
                                // 兜底：个别形态 SORTABLE 作为独立 flag token（无值/带 1/0 值）
                                case "sortable" -> {
                                    if (j + 1 < attr.size()) {
                                        String next = String.valueOf(attr.get(j + 1)).toLowerCase(java.util.Locale.ROOT);
                                        if (next.equals("1") || next.equals("0")
                                                || next.equals("true") || next.equals("false")) {
                                            sortable = !next.equals("0") && !next.equals("false");
                                            j++;
                                        } else {
                                            sortable = true;
                                        }
                                    } else {
                                        sortable = true;
                                    }
                                }
                                default -> { }
                            }
                        }
                        if (alias != null && type != null) {
                            fieldTypes.put(alias, type);
                            fieldSortable.put(alias, sortable);
                        }
                    }
                }
            }
        }
        return new FtIndexInfo(numDocs, fieldTypes, fieldSortable, percentIndexed, indexing);
    }

    /**
     * 打开一个 CDC 阻塞会话：独立连接，供单个消费线程独占使用。
     *
     * <p>XREADGROUP BLOCK 会独占连接直到超时，多源多线程时必须各用各的会话，
     * 否则 BLOCK 命令在共享连接上串行排队，触发命令超时。
     * 会话用完必须 close（消费循环用 try-with-resources 保证）。
     */
    public BlockingSession openBlockingSession() {
        log.debug("打开 CDC 阻塞会话（独立连接）");
        BlockingSession session = new BlockingSession(client.connect());
        blockingSessions.add(session);
        return session;
    }

    /**
     * 强制关闭全部阻塞会话（停机护栏）。
     *
     * <p><b>为什么 interrupt 不够</b>：Lettuce sync 命令的等待对线程 interrupt
     * 不敏感（等的是内部 future，不是可中断锁），XREADGROUP BLOCK 中的消费线程
     * 中断后仍要等 BLOCK 超时才醒——阻塞会话一多（上百路）SIGTERM 优雅关闭会
     * 僵死（僵尸实例可悬空上百条连接数小时）。直接 close 连接立即断开
     * 在途 BLOCK 命令，消费线程当场退出循环。
     */
    public void closeAllBlockingSessions() {
        for (BlockingSession session : blockingSessions) {
            try {
                session.close();
            } catch (Exception e) {
                log.debug("关闭阻塞会话异常（忽略，继续关闭其余会话）: {}", e.getMessage(), e);
            }
        }
    }

    /**
     * 单消费线程独占的阻塞会话，持有独立连接。
     *
     * <p><b>非静态内部类</b>：close 时要从外部类的 {@link #blockingSessions}
     * 登记表注销自己——JDK 21 允许内部类持有静态常量字段，原 static 声明因此移除。
     *
     * <p><b>非线程安全</b>：一个线程一个实例，不可跨线程共享——共享即退化成
     * "多源共用一条连接"，正是本设计要规避的问题。
     */
    public final class BlockingSession implements AutoCloseable {

        private static final Logger log = LoggerFactory.getLogger(BlockingSession.class);

        private final StatefulRedisConnection<String, String> connection;

        /** 防重复关闭：close 会被 try-with-resources 和停机护栏各调一次。 */
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private BlockingSession(StatefulRedisConnection<String, String> connection) {
            this.connection = connection;
        }

        private RedisCommands<String, String> sync() {
            return connection.sync();
        }

        /**
         * XGROUP CREATE（MKSTREAM），已存在则忽略。
         *
         * <p>MKSTREAM 让 stream 不存在时自动创建，避免"Debezium 还没写入第一条数据、
         * 消费者已启动"时因 stream 不存在而失败。
         * BUSYGROUP（group 已存在）是正常的重复启动场景，静默忽略。
         */
        public void xgroupCreate(String streamKey, String group) {
            try {
                sync().xgroupCreate(
                        XReadArgs.StreamOffset.from(streamKey, "0-0"),
                        group,
                        XGroupCreateArgs.Builder.mkstream(true));
                log.debug("consumer group 已创建 stream={} group={}", streamKey, group);
            } catch (RedisBusyException e) {
                // BUSYGROUP：consumer group 已存在，属正常重复启动，不打日志避免刷屏
            }
        }

        /**
         * XREADGROUP，读取新消息（offsetId 传 ">"）。
         *
         * <p>{@code ">"} 表示"只要从未投递给本 group 的新消息"——
         * 这是消费循环与 PEL 重查任务的分工边界：消费循环只读新消息，
         * 失败消息的重新投递交给 reclaim 任务的 XCLAIM，两者互不干扰。
         *
         * <p>只在读到消息时打日志：BLOCK 超时返回空是常态（每秒一次的空转），
         * 打出来会把日志彻底淹没。
         */
        public List<StreamMessage<String, String>> xreadgroup(
                String streamKey, String group, String consumer, String offsetId, int blockMs) {
            XReadArgs.StreamOffset<String> offset =
                    XReadArgs.StreamOffset.from(streamKey, offsetId);
            List<StreamMessage<String, String>> messages = sync().xreadgroup(
                    Consumer.from(group, consumer),
                    XReadArgs.Builder.block(blockMs).count(100),
                    offset);
            if (messages != null && !messages.isEmpty()) {
                log.debug("XREADGROUP 读到消息 stream={} group={} 条数={} 首条id={}",
                        streamKey, group, messages.size(), messages.get(0).getId());
            }
            return messages;
        }

        /** XACK 确认单条消息，把它移出 PEL。 */
        public void xack(String streamKey, String group, String messageId) {
            sync().xack(streamKey, group, messageId);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            blockingSessions.remove(this);
            connection.close();
            log.debug("CDC 阻塞会话已关闭");
        }
    }
}

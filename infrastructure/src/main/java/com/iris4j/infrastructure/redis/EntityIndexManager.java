package com.iris4j.infrastructure.redis;

import com.iris4j.context.schema.EntitySchema;
import com.iris4j.context.schema.FieldSchema;
import com.iris4j.context.schema.FieldType;
import com.iris4j.context.schema.SchemaManager;

import com.iris4j.infrastructure.schema.SchemaReloadedEvent;
import com.iris4j.shared.key.KeyStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Query Engine 二级索引的生命周期管理器。
 *
 * <p><b>职责边界</b>：只管"索引存不存在、定义对不对"，不管查询怎么走——
 * 查询路径的选择（索引/降级）在 {@link LettuceEntityProjectionRepository}。
 *
 * <p><b>触发时机</b>：
 * <ul>
 *   <li>{@link ApplicationReadyEvent}：启动完成后首次同步；</li>
 *   <li>{@link SchemaReloadedEvent}：Schema 热重载后重新同步
 *       （indexed 声明可能变了）。</li>
 * </ul>
 *
 * <p><b>幂等策略</b>：先 FT.INFO 读取现有索引的 别名→类型 映射，与 Schema 推导的
 * 目标定义一致就跳过；不一致或索引不存在才 DROPINDEX + CREATE。
 * ON JSON 索引重建时 Redis 会自动重扫 PREFIX 下全部文档，无数据风险；
 * 重建窗口内（异步索引追平前）查询侧靠降级路径保证正确性。
 *
 * <p><b>失败不阻断启动</b>：建索引失败（如 Redis 未加载 Query Engine 模块）
 * 只 warn + 标记该实体 indexAvailable=false，查询自动走 SCAN 内存过滤——
 * 系统退化为全 SCAN 的降级行为，而不是直接不可用。
 *
 * <p><b>失败自动重试</b>：Redis 重启后的 AOF LOADING 窗口内启动应用，
 * 全部 FT.CREATE 会撞 LOADING 失败。重试不能只挂在 Schema 热重载上
 * （Schema 文件不变就永远不会重试，SCAN 降级变成无限期）：
 * syncAll 结束后若存在失败实体，按 15s 起步、逐轮翻倍（上限 5min）的退避自动重试，
 * 全部恢复后停摆。Redis 就绪后索引自动重建，应用侧无感恢复。
 */
@Component
public class EntityIndexManager {

    private static final Logger log = LoggerFactory.getLogger(EntityIndexManager.class);

    /** 重试退避：首轮延迟 15s，之后逐轮翻倍，上限 5 分钟（AOF LOADING 常见 1~5 分钟量级）。 */
    private static final long RETRY_BASE_DELAY_MS = 15_000;
    private static final long RETRY_MAX_DELAY_MS = 300_000;

    private final RedisAdapter redis;
    private final KeyStrategy keys;
    /** 索引枚举需要 list()，这是管理面能力，所以依赖 SchemaManager 而非只读的 SchemaProvider。 */
    private final SchemaManager schemaManager;

    /** 重试调度器（懒创建，daemon，停机时关闭）。 */
    private ScheduledExecutorService retryScheduler;
    /** 防止同一轮重试重复排队。 */
    private final AtomicBoolean retryQueued = new AtomicBoolean();
    /** 连续全失败轮数（退避依据；有任何恢复即清零）。 */
    private final AtomicInteger consecutiveFailedRounds = new AtomicInteger();

    /** (namespace/entity) -> 索引是否可用。缺席视为可用，由 repository 侧的异常兜底。 */
    private final Map<String, Boolean> indexAvailable = new ConcurrentHashMap<>();

    public EntityIndexManager(
            RedisAdapter redis,
            KeyStrategy keys,
            SchemaManager schemaManager) {
        this.redis = redis;
        this.keys = keys;
        this.schemaManager = schemaManager;
    }

    /** 查询侧探针：该实体的索引是否可用（不可用则走 SCAN 降级）。 */
    public boolean isAvailable(String namespace, String entity) {
        return indexAvailable.getOrDefault(namespace + "/" + entity, true);
    }

    /**
     * 查询侧探针（回填感知）：索引是否<b>可安全走索引路径</b>。
     *
     * <p><b>为什么需要这个</b>：FT.CREATE 之后引擎是<b>异步</b>
     * 回填的，回填期间索引「存在但查不到数据」——FT.SEARCH 返回空，与「表里真的没数据」
     * 无法区分。Agent 拿到空页会误判为「数据被清空」，与历史对话矛盾时陷入推理死循环
     * （模型可能重复「Let's go」数百次直至打满轮数）。
     *
     * <p>判定为未就绪的两种情形：
     * <ul>
     *   <li>建索引失败（indexAvailable=false，走 SCAN 降级）；</li>
     *   <li>引擎仍在回填（percent_indexed&lt;1 或 indexing=1）。</li>
     * </ul>
     * 未就绪时调用方应降级 SCAN——SCAN 直读投影文档，不依赖索引回填状态，
     * 正确性有保证（代价与实体总量成正比，属回填窗口内的可接受降级）。
     */
    public boolean isQueryReady(String namespace, String entity) {
        if (!isAvailable(namespace, entity)) {
            return false;
        }
        try {
            RedisAdapter.FtIndexInfo info = redis.ftInfo(keys.indexKey(namespace, entity));
            return info.backfillComplete();
        } catch (Exception e) {
            // 索引不存在/FT.INFO 失败：不阻断查询，交由 repository 侧异常兜底走 SCAN
            return true;
        }
    }

    /**
     * 索引回填状态描述（供查询结果附加提示、供管理面展示）。
     *
     * @return 就绪返回 null；未就绪返回可直接透出给调用方/模型的说明文案
     */
    public String readinessHint(String namespace, String entity) {
        if (!isAvailable(namespace, entity)) {
            return "该实体索引不可用，已降级为扫描查询（结果正确，性能较低）";
        }
        try {
            RedisAdapter.FtIndexInfo info = redis.ftInfo(keys.indexKey(namespace, entity));
            if (info.backfillComplete()) {
                return null;
            }
            return String.format(
                    "该实体索引正在后台回填（进度 %.1f%%），当前查询已降级为扫描以保证正确性；"
                            + "此时结果可能不完整，请勿据此判断数据不存在，可稍后重试",
                    Math.max(0.0, Math.min(1.0, info.percentIndexed())) * 100.0);
        } catch (Exception e) {
            return null;
        }
    }

    /** 启动完成即同步一次，保证首查之前索引已就绪。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        syncAll(schemaManager.list());
    }

    /** Schema 热重载后重新同步（携带重载后的全量生效 Schema）。 */
    @EventListener
    public void onSchemaReloaded(SchemaReloadedEvent event) {
        log.info("Schema 重载触发索引同步 实体数={}", event.schemas().size());
        syncAll(event.schemas());
    }

    /**
     * 全量同步：对每个 Schema 声明过 indexed 字段的实体，保证索引存在且定义一致。
     *
     * <p>synchronized：启动事件与热重载事件可能并发（热载轮询在后台线程），
     * 同一索引的 DROPINDEX+CREATE 必须串行，否则可能出现"删了没建回来"的窗口。
     */
    public synchronized void syncAll(List<EntitySchema> schemas) {
        boolean anyFailed = false;
        for (EntitySchema schema : schemas) {
            List<RedisAdapter.FtField> target = deriveFields(schema);
            // 完全没有 indexed 字段的实体不需要索引（主键覆盖查询走 JSON.GET）
            if (target.isEmpty()) {
                continue;
            }
            String ns = schema.namespace();
            String entity = schema.entity();
            String indexName = keys.indexKey(ns, entity);
            try {
                if (matchesExisting(indexName, target)) {
                    log.debug("索引定义一致，跳过重建 index={}", indexName);
                    continue;
                }
                redis.ftDropindex(indexName);
                redis.ftCreate(indexName, docPrefix(ns, entity), target);
                indexAvailable.put(ns + "/" + entity, true);
                log.info("索引就绪 index={} 覆盖={} 字段={}",
                        indexName, docPrefix(ns, entity),
                        target.stream().map(RedisAdapter.FtField::alias).toList());
            } catch (Exception e) {
                // 建不出来不阻断启动：标记不可用，查询走 SCAN 降级，交给重试循环恢复
                anyFailed = true;
                indexAvailable.put(ns + "/" + entity, false);
                log.warn("索引创建失败，该实体查询将降级为 SCAN index={} 原因={}",
                        indexName, e.getMessage());
            }
        }
        if (anyFailed) {
            scheduleRetry();
        } else {
            consecutiveFailedRounds.set(0);
        }
    }

    /**
     * 失败实体的退避重试：15s 起步逐轮翻倍（上限 5min），全部恢复后停摆。
     *
     * <p>synchronized 与 syncAll 互斥（同一实例的 DROPINDEX+CREATE 必须串行）。
     */
    private synchronized void scheduleRetry() {
        int failedRounds = consecutiveFailedRounds.getAndIncrement();
        long delay = Math.min(RETRY_BASE_DELAY_MS << Math.min(failedRounds, 20), RETRY_MAX_DELAY_MS);
        if (retryScheduler == null) {
            retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "index-retry");
                t.setDaemon(true);
                return t;
            });
        }
        if (retryQueued.compareAndSet(false, true)) {
            log.info("存在索引创建失败实体，{}ms 后自动重试同步（第 {} 轮）", delay, failedRounds + 1);
            retryScheduler.schedule(() -> {
                retryQueued.set(false);
                log.info("索引同步自动重试开始 实体数={}", schemaManager.list().size());
                syncAll(schemaManager.list());
            }, delay, TimeUnit.MILLISECONDS);
        }
    }

    /** 停机时关闭重试线程。 */
    @EventListener(ContextClosedEvent.class)
    public void stopRetry() {
        if (retryScheduler != null) {
            retryScheduler.shutdownNow();
        }
    }

    /**
     * 幂等比对：现有索引定义与目标定义是否一致。
     *
     * <p>比对 别名→类型 映射 + 别名→SORTABLE 映射。SORTABLE 原本不纳入比对
     * （当时只有主键排序用它）；排序下推（sortField → SORTBY）落地后
     * 索引字段需要 SORTABLE，标记缺失会导致引擎报错——必须纳入比对，
     * 让存量索引在升级后自动重建补齐标记。
     */
    private boolean matchesExisting(String indexName, List<RedisAdapter.FtField> target) {
        try {
            RedisAdapter.FtIndexInfo info = redis.ftInfo(indexName);
            Map<String, String> expectedTypes = new LinkedHashMap<>();
            Map<String, Boolean> expectedSortable = new LinkedHashMap<>();
            for (RedisAdapter.FtField f : target) {
                expectedTypes.put(f.alias(), f.type());
                expectedSortable.put(f.alias(), f.sortable());
            }
            if (expectedTypes.equals(info.fieldTypes())
                    && expectedSortable.equals(info.fieldSortable())) {
                return true;
            }
            log.warn("索引定义不一致 index={} expectedTypes={} actualTypes={} expectedSortable={} actualSortable={}",
                    indexName, expectedTypes, info.fieldTypes(), expectedSortable, info.fieldSortable());
            return false;
        } catch (Exception e) {
            // Unknown Index = 索引不存在，走新建；其他异常同样交由调用方按"需重建"处理
            return false;
        }
    }

    /**
     * 从 Schema 推导目标索引字段：只取声明了索引形态的字段，全部标 SORTABLE。
     *
     * <p><b>全字段 SORTABLE（排序下推的前提）</b>：SORTBY 只能排 SORTABLE 字段，
     * 之前只有主键标 SORTABLE（稳定分页用）；排序参数（sortField）落地后，
     * 等值/范围/数值过滤字段的排序是 Agent 的常见诉求——TAG/NUMERIC 的
     * SORTABLE 代价是索引体积略增，收益是任意索引字段可引擎内排序（含 DESC），
     * 远优于全量拉回内存排。
     *
     * <p>形态判定统一走 {@link SearchQueryTranslator#indexTypeOf(FieldSchema)}
     * （显式 index 声明优先，缺省回落 indexed 布尔推导），与查询翻译侧共用
     * 同一套规则——建索引的形态和下推查询的形态必须同源。
     * {@code index: text} 即建 TEXT 字段（无需 indexed: true）。
     *
     * <p>路径固定 {@code $.<字段名>}——投影文档是扁平字段映射，没有嵌套结构。
     */
    private List<RedisAdapter.FtField> deriveFields(EntitySchema schema) {
        List<RedisAdapter.FtField> fields = new ArrayList<>();
        for (FieldSchema f : schema.fields()) {
            SearchQueryTranslator.FtType ftType = SearchQueryTranslator.indexTypeOf(f);
            if (ftType == null) {
                continue;
            }
            // 穷举 FtType 全部取值（switch 表达式编译期强制完整）
            String type = switch (ftType) {
                case TAG -> "TAG";
                case NUMERIC -> "NUMERIC";
                case TEXT -> "TEXT";
            };
            fields.add(new RedisAdapter.FtField("$." + f.name(), f.name(), type, true));
        }
        return fields;
    }

    /** 文档覆盖前缀：实体 key 模板的主键段传空，得到 {@code iris:{ns}:entity:{entity}:}。 */
    private String docPrefix(String namespace, String entity) {
        return keys.entityKey(namespace, entity, "");
    }
}

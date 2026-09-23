package com.iris.lite.java.cdc;

import com.iris.lite.java.application.ops.DlqAdminService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iris.lite.java.application.query.ProjectionBatchApplier;
import com.iris.lite.java.infrastructure.redis.RedisAdapter;
import com.iris.lite.java.shared.metrics.IrisMetrics;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.models.stream.PendingMessage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CDC 消费者：从 Redis Stream（Debezium 写入）读取变更事件，解析后交给
 * {@link ChangeEventHandler} 投影，并确认消费位点。
 *
 * <p><b>多源</b>：每个 {@link CdcProperties.Source} 独立线程消费，互不阻塞。
 * 每个线程通过 {@link RedisAdapter#openBlockingSession()} 独占一条 Redis 连接——
 * 共享连接会让多个 BLOCK 命令串行排队，后到的源读不到消息最终撞命令超时。
 *
 * <p><b>可靠性语义（毒消息防护）</b>：
 * <ul>
 *   <li>处理失败【不 XACK】，消息留在 PEL，不阻塞后续新消息的消费；</li>
 *   <li>{@link #reclaimPending()} 按 reclaim-interval-ms 周期扫描 PEL：
 *       空闲超过 claim-idle-ms 的消息用 XCLAIM 重领，<b>先真正处理一次</b>，
 *       失败且已达 max-deliveries 才整条写入 DLQ 流（{stream}:dlq）后 XACK 移出 PEL；</li>
 *   <li>毒消息不再被静默 XACK 丢弃，可通过 DLQ 管理入口（DlqAdminService）人工重放。</li>
 * </ul>
 *
 * <p><b>多实例隔离</b>：消费端 id = 配置基名 + 实例标识（见 {@link #INSTANCE_ID}）。
 * 配置里那样写而不在 yml 里逐表硬编码，是为了让同一份配置能安全地起多个实例。
 *
 * <p><b>消费线程与 reclaim 任务的分工（关键）</b>：
 * 消费线程只读 {@code ">"}（从未投递过的新消息），从不重读 PEL；
 * PEL 的处理完全交给 {@link #reclaimPending()} 定时任务。
 * 这样一条失败消息不会阻塞后续的新消息，也不会被同一个循环反复重试打满日志。
 */
@Component
public class CdcConsumer {

    private static final Logger log = LoggerFactory.getLogger(CdcConsumer.class);

    /** 消费循环异常后的重连退避毫秒（日志文案与实际退避由此常量统一标准）。 */
    private static final long RECONNECT_DELAY_MS = 2000;

    /** CDC 事件计数指标名（result tag：ok/fail）。 */
    private static final String METRIC_EVENTS = "iris.cdc.events";

    /** 消费线程非正常退出指标（reason tag：oom / 异常类名），正常 stop 不计。 */
    private static final String METRIC_THREAD_EXIT = "iris.cdc.thread.exit";

    /** XREADGROUP 的 ">" 语义：只取从未投递给本 group 的新消息。 */
    private static final String NEW_MESSAGES = ">";

    /** DLQ 流名后缀：{originStream}:dlq。 */
    /** DLQ 流名后缀（包内唯一出处：DefaultDlqAdminService / DefaultCdcInsightService 均引用本常量）。 */
    public static final String DLQ_SUFFIX = ":dlq";

    /** 单次 PEL 扫描上限，避免 PEL 很大时一次拉取过多。 */
    private static final int RECLAIM_BATCH = 100;

    /** 每处理 N 个非空批次做一次 XTRIM（stream 裁剪节流，不逐批执行）。 */
    private static final int TRIM_EVERY_BATCHES = 25;

    /**
     * 裁剪安全余量：实际 maxlen 至少比「未消费 + 未确认」多这么多条。
     *
     * <p>XTRIM 用的是近似裁剪（approximateTrimming=true，按 macro node 粒度整块丢弃，
     * 单块可达 100 条），故余量取 1000：既覆盖近似误差，也覆盖"取完 lag 到真正执行
     * XTRIM 之间生产者又写入若干条"的窗口。
     */
    private static final long TRIM_SAFETY_MARGIN = 1000;

    /** 失败原因截断长度：完整堆栈可能几 KB，塞进 DLQ 字段太占空间。 */
    private static final int ERROR_MAX_LEN = 500;

    /** envelope 反序列化类型（无状态常量，每消息新建匿名实例是纯浪费）。 */
    private static final TypeReference<Map<String, Object>> ENVELOPE_TYPE = new TypeReference<>() {
    };

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /**
     * 本实例标识：拼接到每个 source 的消费端 id 之后。
     *
     * <p><b>为什么必须有</b>：消费端 id 原本是配置里的固定值 {@code cdc-{ns}-{table}}，
     * 不含实例标识。于是同一套配置起两个实例（典型场景：IDEA 里跑一个 + 命令行跑一个，
     * 或 k8s 扩容出第二个副本）时，两者在 Redis 眼里是<b>同一个消费者</b>——
     * 各自的 reclaim 任务都会把对方的在途消息当成"过期未确认"抢走（XCLAIM），
     * 投递次数被凭空推高到 max-deliveries，消息还没被处理就被判成毒消息。
     * 后果：多条正常消息一起误入 DLQ，且 error 只能记成 unknown。
     *
     * <p><b>取值</b>：优先环境变量 {@code IRIS_INSTANCE_ID}（k8s 可直接注入 pod 名），
     * 否则 {@code 主机名-pid}——同机上多个 JVM 的 pid 必然不同，足以区分。
     */
    private static final String INSTANCE_ID = resolveInstanceId();

    /** 实例标识里主机段的截断长度：主机名过长会淹没消费端 id 的可读性。 */
    private static final int INSTANCE_ID_HOST_MAX = 24;

    private final RedisAdapter redis;
    private final ChangeEventHandler handler;
    private final CdcProperties props;
    private final ObjectMapper objectMapper;
    /** 批量投影提交（pipeline 化）；不可用时自动回落逐条路径。 */
    private final ProjectionBatchApplier batchApplier;
    /** 批量 pipeline 开关：false 时恒走逐条路径（排障用）。 */
    private final boolean batchPipelineEnabled;
    /** 跨表携带字段：子表写入回填 + 主表变更回刷。 */
    private final CarriedFieldSupport carriedFieldSupport;

    private volatile boolean running = true;
    /** 启动幂等守卫：start() 只允许执行一次（防管理端点/运维脚本误触重复启动，重复调用静默忽略）。 */
    private final AtomicBoolean started = new AtomicBoolean();
    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    /** 消息 id -> 最近一次失败原因；转 DLQ 时随条目记录，成功/DLQ 后清除。 */
    private final Map<String, String> lastErrors = new ConcurrentHashMap<>();

    public CdcConsumer(
            RedisAdapter redis,
            ChangeEventHandler handler,
            CdcProperties props,
            ObjectMapper objectMapper,
            ObjectProvider<ProjectionBatchApplier> batchApplierProvider,
            CarriedFieldSupport carriedFieldSupport,
            @Value("${iris.cdc.batch-pipeline-enabled:true}") boolean batchPipelineEnabled) {
        this.redis = redis;
        this.handler = handler;
        this.props = props;
        this.objectMapper = objectMapper;
        this.batchApplier = batchApplierProvider == null ? null : batchApplierProvider.getIfAvailable();
        this.carriedFieldSupport = carriedFieldSupport;
        this.batchPipelineEnabled = batchPipelineEnabled;
    }

    /**
     * 启动：为每个 source 起一个守护线程。
     *
     * <p><b>为什么挂 ApplicationReadyEvent 而不是 @PostConstruct</b>：
     * @PostConstruct 阶段 context 尚未 refresh 完成，其他 bean 的初始化
     * （schema 加载、模型加载等重活）可能还没跑完；而消费线程一起动就开始消化
     * Debezium 积压，"依赖未就绪"窗口内的连续失败在 max-deliveries=3 下
     * 约一分钟就会把正常消息误送 DLQ（可回放但污染排查标准）。
     * 改在应用完全就绪后启动，消费起点后移到所有 bean 初始化完毕之后。
     *
     * <p><b>daemon 线程</b>：不阻止 JVM 退出——CDC 消费是后台任务，
     * 容器关闭时应随主线程一起结束，而不是让 JVM 挂着等它。
     *
     * <p><b>单源失败不连坐</b>：每个 source 的 gauge 注册/线程创建/启动独立
     * try/catch——任何一个抛异常（含 native 线程耗尽这类 Error），其余 source
     * 照常启动，结束后对未启动数量打明确的汇总告警，不再"部分启动且无诊断"。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!started.compareAndSet(false, true)) {
            log.warn("CDC 消费者已启动过，忽略重复启动请求");
            return;
        }
        if (props.sources().isEmpty()) {
            log.warn("未配置任何 CDC source，消费者不启动");
            return;
        }
        int launched = 0;
        for (CdcProperties.Source source : props.sources()) {
            try {
                // gauge 注册不含 Redis I/O（取值都在 Prometheus 抓取时才发生），
                // 放线程启动前：注册失败时该源干脆不起线程，不产生"线程在跑但没监控"的半残状态
                registerBacklogGauges(source);
                // 线程名带上 entity，日志里靠它区分是哪个数据源
                Thread t = new Thread(() -> consumeLoop(source),
                        "iris-cdc-" + source.entity());
                t.setDaemon(true);
                // 兜底留痕：consumeLoop 已尽量自愈，若仍有 Throwable 漏出
                // （如日志组件自身抛错），至少 iris 日志 + 指标可见，不再只打 stderr
                t.setUncaughtExceptionHandler((th, e) -> {
                    IrisMetrics.increment(METRIC_THREAD_EXIT,
                            "entity", source.entity(), "reason", e.getClass().getSimpleName());
                    log.error("CDC 消费线程异常终止 stream={} entity={} thread={}",
                            source.stream(), source.entity(), th.getName(), e);
                });
                threads.add(t);
                t.start();
                launched++;
                log.info("CDC 消费者已启动: stream={} group={} consumer={} entity={} maxDeliveries={} claimIdleMs={} instance={}",
                        source.stream(), source.group(), consumerId(source), source.entity(),
                        props.maxDeliveries(), props.claimIdleMs(), INSTANCE_ID);
            } catch (Throwable e) {
                // 线程创建/启动可能抛 Error（线程资源耗尽、OOM）；
                // 单源失败只记日志，绝不能中断其余 source 的启动
                log.error("CDC 消费者单源启动失败（其余 source 继续）stream={} entity={}",
                        source.stream(), source.entity(), e);
            }
        }
        if (launched < props.sources().size()) {
            log.error("CDC 消费者仅启动 {}/{} 个 source，未启动的流将停止消费（lag 持续增长），请按上方失败日志逐源排查",
                    launched, props.sources().size());
        }
    }

    /**
     * 注册单个 source 的积压类 Gauge：
     * iris.cdc.stream.backlog = stream 当前保留条数（XLEN，O(1)）；
     * iris.cdc.pel = 已投递未确认条数（XPENDING 摘要，PEL 小时成本可忽略）。
     *
     * <p><b>标准注意</b>：XLEN 是"保留在 stream 里的条数"（受 stream-maxlen
     * 裁剪约束），不是消费积压——积压看 XINFO GROUPS 的 lag（控制台 Cdc 页标准）。
     *
     * <p>指标抓取时才取值，取值失败（如 group 刚被重建）按 0 处理——
     * Gauge 异常不能打断 Prometheus 抓取请求。
     */
    private void registerBacklogGauges(CdcProperties.Source source) {
        IrisMetrics.gauge("iris.cdc.stream.backlog",
                () -> {
                    try {
                        return (double) redis.xlen(source.stream());
                    } catch (Exception e) {
                        log.debug("backlog gauge 取值失败 stream={}，按 0 处理", source.stream(), e);
                        return 0d;
                    }
                },
                "stream", source.stream(), "entity", source.entity());
        IrisMetrics.gauge("iris.cdc.pel",
                () -> {
                    try {
                        return (double) redis.xpendingCount(source.stream(), source.group());
                    } catch (Exception e) {
                        log.debug("PEL gauge 取值失败 stream={}，按 0 处理", source.stream(), e);
                        return 0d;
                    }
                },
                "stream", source.stream(), "entity", source.entity());
    }

    /**
     * 停止：置停止标志、中断所有消费线程、<b>强制关闭全部阻塞会话</b>。
     *
     * <p><b>为什么必须关连接</b>：Lettuce sync 命令的等待对线程 interrupt 不敏感，
     * 只 interrupt 的话 BLOCK 中的线程要等 blockMs 超时才醒——阻塞会话一多
     * （上百路）就会把 SIGTERM 优雅关闭拖成僵尸（连接悬空数小时）。直接 close 连接立即
     * 断开在途 BLOCK，线程当场退出循环走 finally 清理。
     *
     * <p>最后 join 3 秒确认线程退出；daemon 属性保证即使有残留也不阻塞 JVM。
     */
    @PreDestroy
    public void stop() {
        running = false;
        threads.forEach(Thread::interrupt);
        redis.closeAllBlockingSessions();
        for (Thread t : threads) {
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long alive = threads.stream().filter(Thread::isAlive).count();
        if (alive > 0) {
            log.warn("CDC 消费线程 {} 个未在 3s 内退出（daemon，不阻塞 JVM 退出）", alive);
        }
        log.info("CDC 消费者停止完成，线程数={} 残留={}", threads.size(), alive);
    }

    /**
     * 单个源的消费主循环。
     *
     * <p><b>连接独占</b>：整个循环持有独立连接（try-with-resources 保证关闭）。
     * XREADGROUP BLOCK 会独占连接直到超时返回，多源共享连接会让 BLOCK 串行排队，
     * 触发命令超时。
     */
    private void consumeLoop(CdcProperties.Source source) {
        // 外层重试：openBlockingSession 的失败也纳入重试——Redis 未就绪/重启窗口内
        // 建连失败若不重试，消费线程会直接死亡（无业务日志、无自愈），source 永久停摆
        while (running) {
            try (RedisAdapter.BlockingSession session = redis.openBlockingSession()) {
                // group 创建成功后置位，避免每次循环都尝试创建（BUSYGROUP 虽被忽略但仍是多余往返）
                boolean groupReady = false;
                // XTRIM 节流计数：每 TRIM_EVERY_BATCHES 个非空批次裁剪一次
                int batchesSinceTrim = 0;
                // 连续故障计数：同一连接上反复失败多半是连接已死（而非单条命令问题），
                // 连续超过阈值就跳出内层，重建连接——避免死循环空转
                int consecutiveFailures = 0;
                while (running) {
                    try {
                        if (!groupReady) {
                            session.xgroupCreate(source.stream(), source.group());
                            groupReady = true;
                            // 启动即裁剪一次：上次运行留下的历史事件立即压回到 maxlen 内
                            trimStream(source);
                            // 启动即清理一次陈旧消费端：实例标识使每次重启都会新增条目
                            pruneStaleConsumers(source);
                        }
                        List<StreamMessage<String, String>> messages = session.xreadgroup(
                                source.stream(), source.group(), consumerId(source),
                                NEW_MESSAGES, source.blockMs());
                        consecutiveFailures = 0;
                        if (messages != null && !messages.isEmpty()) {
                            boolean allHandled;
                            if (batchPipelineEnabled && batchApplier != null) {
                                allHandled = processBatch(messages, source, session);
                            } else {
                                allHandled = processOneByOne(messages, source, session);
                            }
                            // stream 裁剪：已消费事件按 maxlen 近似裁剪，防长期运行无限增长。
                            // 只在非空批次计数，空闲轮询不产生 XTRIM 往返
                            if (allHandled && ++batchesSinceTrim >= TRIM_EVERY_BATCHES) {
                                batchesSinceTrim = 0;
                                trimStream(source);
                            }
                        }
                    } catch (Exception e) {
                        // 停止信号触发的中断异常，直接退出循环
                        if (!running) {
                            break;
                        }
                        log.error("CDC 消费循环异常 stream={}，{}ms 后重试", source.stream(), RECONNECT_DELAY_MS, e);
                        // 连接可能已不可用，下次循环重建 group 标记并重新创建
                        groupReady = false;
                        sleepQuietly(RECONNECT_DELAY_MS);
                        if (++consecutiveFailures >= 30) {
                            // ≈1 分钟连续失败：判定连接已死，跳出内层重建会话
                            log.error("CDC 消费循环连续失败 {} 次，重建消费连接 stream={}",
                                    consecutiveFailures, source.stream());
                            break;
                        }
                    }
                }
            } catch (Throwable e) {
                if (!running) {
                    break;
                }
                if (e instanceof OutOfMemoryError) {
                    // OOM 下退避重试只会加剧内存压力：退出本线程把问题暴露出来，
                    // 其余源照常消费；未 XACK 的消息仍留 PEL，重启进程后可恢复
                    log.error("CDC 消费线程内存耗尽，退出本源消费（需重启进程恢复）stream={}",
                            source.stream(), e);
                    IrisMetrics.increment(METRIC_THREAD_EXIT,
                            "entity", source.entity(), "reason", "oom");
                    break;
                }
                // catch Throwable 而非 Exception：NoClassDefFoundError /
                // ExceptionInInitializerError 这类 Error 会直接击穿循环——
                // 线程静默死亡，iris 日志零痕迹（只有 JVM 默认 handler 打到 stderr），
                // 该源 lag 持续增长且无自愈。Error 多半不可自愈，但退避重试保证
                // 它持续可见，且消息未 XACK 留在 PEL 不丢
                log.error("CDC 消费连接建立/维持失败（含 Error 级异常，将重建会话）stream={}，{}ms 后重建",
                        source.stream(), RECONNECT_DELAY_MS, e);
                sleepQuietly(RECONNECT_DELAY_MS);
            }
        }
        log.info("CDC 消费者已停止: stream={}", source.stream());
    }

    /**
     * XTRIM 裁剪到 stream-maxlen：消费完的历史事件没有保留价值，
     * 不裁剪的话 XLEN 只增不减（Debezium 消费不删事件），长期运行吃穿内存。
     * 裁剪失败只记日志——裁剪是运维增强，绝不能打断消费循环。
     *
     * <p><b>maxlen 只是「保留上限」，绝不能小于未消费量。</b>
     * 若直接 {@code xtrim(stream, streamMaxlen)}，一旦积压超过 maxlen，
     * XTRIM MAXLEN 会把**尚未投递**的事件一并裁掉，且被裁掉的消息不在 PEL 里，
     * 重试与 DLQ 都兜不住——静默丢数据。
     *
     * <p>典型风险场景：全量快照的产出速率（约 2.5 万行/秒）远高于消费速率
     * （约 2500 条/秒），单表 20 万行的快照可瞬间把该流顶到 20 万条，而
     * maxlen=100000。此时 {@code lag=0} 看起来"全部消费完毕"——因为 group 的
     * last-delivered-id 被推到尾部，丢失被完全掩盖，极难察觉。
     *
     * <p>因此实际生效上限取 {@code max(streamMaxlen, lag + pending + 余量)}。
     * <b>lag 读不到时宁可不裁剪</b>：裁剪是可选的运维优化，丢数据不可逆。
     */
    private void trimStream(CdcProperties.Source source) {
        try {
            Long lag = redis.xinfoGroupLag(source.stream(), source.group());
            if (lag == null) {
                log.debug("CDC stream 跳过裁剪（读不到 lag）stream={}", source.stream());
                return;
            }
            long pending;
            try {
                pending = redis.xpendingCount(source.stream(), source.group());
            } catch (Exception e) {
                // PEL 读不到就按最保守处理：不裁剪
                log.debug("CDC stream 跳过裁剪（读不到 pending）stream={}: {}", source.stream(), e.getMessage(), e);
                return;
            }
            long floor = lag + pending + TRIM_SAFETY_MARGIN;
            long safeMaxlen = Math.max(props.streamMaxlen(), floor);
            long trimmed = redis.xtrim(source.stream(), safeMaxlen);
            if (trimmed > 0) {
                log.info("CDC stream 已裁剪 stream={} 裁掉={} 配置上限={} 实际上限={}（保护未消费 lag={} pending={}）",
                        source.stream(), trimmed, props.streamMaxlen(), safeMaxlen, lag, pending);
            }
        } catch (Exception e) {
            log.warn("CDC stream 裁剪失败（不影响消费）stream={}: {}", source.stream(), e.getMessage(), e);
        }
    }

    /**
     * 清理本 group 下的陈旧消费端。启动时对每条流执行一次。
     *
     * <p><b>为什么需要</b>：消费端 id 带上实例标识后，每次重启都会留下一个新条目，
     * 而 Redis 从不自动回收（XINFO CONSUMERS 只增不减）。135 条流各自累积，
     * 会让「消费端数量」这个观测标准彻底失去意义。
     *
     * <p><b>只删 pending == 0 的，这是硬约束</b>：XGROUP DELCONSUMER 会连带丢弃
     * 该消费端的 PEL 条目，那些消息将不再被重投也不进 DLQ——等于静默丢消息。
     * pending 为 0 表示已全部确认（或该进程已退出且消息已被回收），
     * 此时删除没有任何信息损失。本实例自己的条目始终保留。
     *
     * <p>清理失败只记日志：它是运维增强，不是消费正确性的依赖。
     */
    private void pruneStaleConsumers(CdcProperties.Source source) {
        try {
            String self = consumerId(source);
            Map<String, Long> consumers = redis.xinfoConsumers(source.stream(), source.group());
            int removed = 0;
            for (Map.Entry<String, Long> e : consumers.entrySet()) {
                if (self.equals(e.getKey()) || e.getValue() > 0) {
                    continue;
                }
                redis.xgroupDelConsumer(source.stream(), source.group(), e.getKey());
                removed++;
            }
            if (removed > 0) {
                log.info("CDC 陈旧消费端已清理 stream={} group={} 删除={} 保留={}",
                        source.stream(), source.group(), removed, self);
            }
        } catch (Exception e) {
            log.warn("CDC 陈旧消费端清理失败（不影响消费）stream={}: {}",
                    source.stream(), e.getMessage());
        }
    }

    /** 单条消息解析结果：WRITE=数据事件；SKIP=空 body/tombstone/非数据事件；FAIL=解析失败。 */
    private enum ParseKind { WRITE, SKIP, FAIL }

    private record ParsedMessage(ParseKind kind, ProjectionBatchApplier.ProjectionWrite write,
                                 String failReason, String payload) {
    }

    /**
     * 解析单条 stream 消息为投影操作（不做任何 Redis I/O）。
     *
     * <p>Debezium Redis sink 用 record key 作为 stream field，envelope JSON 作为 field 值，
     * 因此不依赖固定字段名，直接取 body 的第一个 value。
     * （陷阱：stream field 名不是固定的 "payload" 而是 record key——
     *   按固定字段名取值只会拿到 null，表现为"事件进来了但没投影"）
     */
    private ParsedMessage parseMessage(StreamMessage<String, String> message,
                                       CdcProperties.Source source) {
        Map<String, String> body = message.getBody();
        String payload = (body == null || body.isEmpty()) ? null : body.values().iterator().next();
        if (payload == null || payload.isBlank()) {
            log.debug("stream 消息 body 为空，跳过 stream={} id={}", source.stream(), message.getId());
            return new ParsedMessage(ParseKind.SKIP, null, null, null);
        }
        // 数据事件（envelope）必然以 { 开头；tombstone 等裸字符串事件不携带数据，静默跳过。
        // 这是防御层：即使 Debezium 侧配置了 tombstones.on.delete=false，其他裸事件也不该报错。
        if (!payload.trim().startsWith("{")) {
            log.debug("跳过非数据事件 stream={} id={} payload={}",
                    source.stream(), message.getId(), payload);
            return new ParsedMessage(ParseKind.SKIP, null, null, null);
        }
        try {
            Map<String, Object> envelope = objectMapper.readValue(payload, ENVELOPE_TYPE);
            String op = String.valueOf(envelope.get("op"));
            @SuppressWarnings("unchecked")
            Map<String, Object> before = (Map<String, Object>) envelope.get("before");
            @SuppressWarnings("unchecked")
            Map<String, Object> after = (Map<String, Object>) envelope.get("after");
            // 携带字段回填：子表行 upsert 前按 Schema 声明从主表投影行补齐
            // 携带字段（原地改 after）。内部全 catch——失败只缺字段，等主表回刷，
            // 不把增强失败升级成事件失败（那会进重试/DLQ，代价完全不成比例）
            carriedFieldSupport.fillChild(source.namespace(), source.entity(), after);
            return new ParsedMessage(ParseKind.WRITE,
                    new ProjectionBatchApplier.ProjectionWrite(
                            source.namespace(), source.entity(), op, before, after),
                    null, payload);
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            return new ParsedMessage(ParseKind.FAIL, null,
                    reason.length() > ERROR_MAX_LEN ? reason.substring(0, ERROR_MAX_LEN) : reason,
                    payload);
        }
    }

    /** 记录一条消息的处理失败（供转 DLQ 带原因 + 指标定位）。 */
    private void recordFailure(StreamMessage<String, String> message,
                               CdcProperties.Source source, String reason) {
        lastErrors.put(message.getId(), reason);
        IrisMetrics.increment(METRIC_EVENTS, "entity", source.entity(), "result", "fail");
    }

    /** 逐条路径（批 pipeline 关闭/不可用时的回落，语义与批路径一致）。 */
    private boolean processOneByOne(List<StreamMessage<String, String>> messages,
                                    CdcProperties.Source source,
                                    RedisAdapter.BlockingSession session) {
        for (StreamMessage<String, String> message : messages) {
            if (process(message, source)) {
                session.xack(source.stream(), source.group(), message.getId());
            } else {
                // 失败不 XACK：留在 PEL，等 reclaimPending 重试或转 DLQ
                log.warn("CDC 事件处理失败，保留 pending 等待重试/转 DLQ stream={} id={}",
                        source.stream(), message.getId());
            }
        }
        return true;
    }

    /**
     * 批量 pipeline 路径：整批解析 → 一次 pipeline 提交 →
     * 按结果逐条 XACK。往返次数从「每事件 5+N 次」压到「整批 2 次 + XACK 数次」，
     * 全量导入吞吐的治本优化。
     *
     * @return 全批是否全部成功（用于 XTRIM 节流计数——有失败时不裁剪，
     *         让未确认消息在 stream 里多留一会儿，避免重投时已被裁掉）
     */
    private boolean processBatch(List<StreamMessage<String, String>> messages,
                                 CdcProperties.Source source,
                                 RedisAdapter.BlockingSession session) {
        List<ProjectionBatchApplier.ProjectionWrite> writes = new ArrayList<>(messages.size());
        List<StreamMessage<String, String>> writeMessages = new ArrayList<>(messages.size());
        // writeMessages 对应其在 messages 中的原始下标（WRITE 是 messages 的顺序子序列，
        // 记录下标免去回填时的 O(n²) 引用扫描）
        List<Integer> writeIdx = new ArrayList<>(messages.size());
        // 每条消息的处置：true=可 XACK；false=留 PEL。SKIP 消息直接置 true。
        boolean[] ack = new boolean[messages.size()];
        boolean allOk = true;

        for (int i = 0; i < messages.size(); i++) {
            StreamMessage<String, String> message = messages.get(i);
            ParsedMessage parsed = parseMessage(message, source);
            switch (parsed.kind()) {
                case SKIP -> {
                    ack[i] = true;
                    // 重试轮里解析成 SKIP（tombstone/空 body）也清失败留痕，防慢性泄漏
                    lastErrors.remove(message.getId());
                }
                case FAIL -> {
                    recordFailure(message, source, parsed.failReason());
                    log.error("CDC 事件解析失败 stream={} id={} payload={}",
                            source.stream(), message.getId(), parsed.payload());
                    allOk = false;
                }
                case WRITE -> {
                    writes.add(parsed.write());
                    writeMessages.add(message);
                    writeIdx.add(i);
                }
                // ParseKind 仅 SKIP/FAIL/WRITE 三值；兜底防御未来新增枚举值时静默漏处置
                default -> throw new IllegalStateException("未支持的消息处置类型: " + parsed.kind());
            }
        }

        List<Boolean> results;
        try {
            results = batchApplier.apply(writes);
        } catch (Exception e) {
            // 连接级故障：整批（含解析成功的 WRITE 消息）留 PEL，等 reclaim 重试
            log.error("批投影 pipeline 提交失败，整批留 pending stream={} count={} err={}",
                    source.stream(), writes.size(), e.getMessage());
            for (StreamMessage<String, String> message : writeMessages) {
                recordFailure(message, source, "BatchPipeline: " + e.getMessage());
            }
            return false;
        }

        int okCount = 0;
        for (int j = 0; j < writeMessages.size(); j++) {
            StreamMessage<String, String> message = writeMessages.get(j);
            boolean ok = j < results.size() && results.get(j);
            ack[writeIdx.get(j)] = ok;
            if (ok) {
                okCount++;
                lastErrors.remove(message.getId());
                IrisMetrics.increment(METRIC_EVENTS, "entity", source.entity(), "result", "ok");
                // 主表携带字段回刷（与单条路径同语义；delete 不刷）
                ProjectionBatchApplier.ProjectionWrite w = writes.get(j);
                if (!"d".equals(w.op())) {
                    carriedFieldSupport.propagateFromParent(
                            w.namespace(), w.entity(), w.before(), w.after(), w.op());
                }
            } else {
                allOk = false;
                recordFailure(message, source, "批投影单条失败（见服务端日志）");
                log.warn("CDC 事件批投影失败，保留 pending 等待重试/转 DLQ stream={} id={}",
                        source.stream(), message.getId());
            }
        }
        // XACK 逐条发但同一连接；批内条数通常 = XREADGROUP COUNT，往返可接受
        // （XACK 本身支持多 id，但失败粒度需要逐条判定，这里只 ack 成功的）
        for (int i = 0; i < messages.size(); i++) {
            if (ack[i]) {
                session.xack(source.stream(), source.group(), messages.get(i).getId());
            }
        }
        log.debug("CDC 批次已处理 count={} ok={} 批pipeline=on", messages.size(), okCount);
        return allOk;
    }

    /** writeMessages 与 messages 同引用相等，回传其在原列表中的下标。 */
    private int writeMessageIndex(List<StreamMessage<String, String>> messages,
                                  StreamMessage<String, String> message) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) == message) {
                return i;
            }
        }
        throw new IllegalStateException("批内消息丢失: " + message.getId());
    }

    /**
     * 处理单条消息：解析 envelope -> 交给 handler 投影。
     *
     * @return true = 已处理（含空 body / tombstone 等无需处理的情况），可 XACK；
     *         false = 处理失败，不 XACK，留在 PEL 等待重试/转 DLQ。
     */
    private boolean process(StreamMessage<String, String> message, CdcProperties.Source source) {
        ParsedMessage parsed = parseMessage(message, source);
        if (parsed.kind() == ParseKind.SKIP) {
            // 重试轮里解析成 SKIP（tombstone/空 body）：清掉之前失败留痕防慢性泄漏
            lastErrors.remove(message.getId());
            return true;
        }
        if (parsed.kind() == ParseKind.FAIL) {
            recordFailure(message, source, parsed.failReason());
            log.error("CDC 事件解析失败 stream={} id={} payload={}",
                    source.stream(), message.getId(), parsed.payload());
            return false;
        }
        try {
            ProjectionBatchApplier.ProjectionWrite w = parsed.write();
            handler.handle(new ChangeEvent(w.op(), w.before(), w.after()),
                    w.namespace(), w.entity());
            // 主表携带字段回刷：主表行的来源字段变化时把新值刷进子表行
            // （c/r 新行必刷，u 仅值变化才刷；delete 不刷——携带无删除语义）
            if (!"d".equals(w.op())) {
                carriedFieldSupport.propagateFromParent(
                        w.namespace(), w.entity(), w.before(), w.after(), w.op());
            }
            lastErrors.remove(message.getId());
            IrisMetrics.increment(METRIC_EVENTS, "entity", source.entity(), "result", "ok");
            log.debug("CDC 事件已处理 stream={} id={} op={}", source.stream(), message.getId(), w.op());
            return true;
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            // 记录失败原因供转 DLQ 时带上；截断避免超长堆栈塞满字段
            recordFailure(message, source,
                    reason.length() > ERROR_MAX_LEN ? reason.substring(0, ERROR_MAX_LEN) : reason);
            log.error("CDC 事件处理失败 stream={} id={} payload={}",
                    source.stream(), message.getId(), parsed.payload(), e);
            return false;
        }
    }

    /**
     * 周期重查 PEL：空闲超过 claim-idle-ms 的消息重试或转 DLQ。
     *
     * <p>单线程调度，与消费线程互不干扰——消费线程只读 ">" 新消息，从不重读 PEL。
     * 这样一条毒消息不会阻塞后续新数据。
     *
     * <p>无 pending 时静默返回（每 reclaim-interval-ms 一次的空扫描不该产生日志）。
     *
     * <p><b>启动前跳过</b>：调度任务在 ContextRefreshedEvent 即生效，早于
     * {@link #start()} 所在的 ApplicationReadyEvent——消费线程尚未起跑的窗口内
     * 不做 XCLAIM，避免依赖未就绪期的重复处理。
     */
    @Scheduled(fixedDelayString = "${iris.cdc.reclaim-interval-ms:15000}")
    public void reclaimPending() {
        if (!running || !started.get() || props.sources().isEmpty()) {
            return;
        }
        for (CdcProperties.Source source : props.sources()) {
            try {
                List<PendingMessage> pending =
                        redis.xpending(source.stream(), source.group(), RECLAIM_BATCH);
                for (PendingMessage p : pending) {
                    // 注意 Lettuce 7 API：getMsSinceLastDelivery / getRedeliveryCount
                    // （redeliveryCount 值 = Redis XPENDING 的投递次数，首次投递计 1）
                    // 空闲时间不够 = 可能正在被处理，跳过避免重复投递。
                    // 本地调试用的 claim-idle-ms=3000 是提速值：单条处理耗时若超过它，
                    // 即便只有一个实例，单轮 reclaim 也会重复投递自己——生产应放大到 30000。
                    if (p.getMsSinceLastDelivery() < props.claimIdleMs()) {
                        continue;
                    }
                    // 重试与转 DLQ 都要先真正处理一次再决定，见 retryOrDlq
                    retryOrDlq(source, p);
                }
            } catch (Exception e) {
                log.warn("CDC PEL 重查失败 stream={}: {}", source.stream(), e.getMessage(), e);
            }
        }
    }

    /**
     * 重领一条 pending 消息并<b>先真正处理</b>，再决定确认还是转 DLQ。
     *
     * <p><b>为什么必须先处理再判 DLQ</b>：若「投递次数 &gt;= maxDeliveries 就直接
     * moveToDlq、不调用 process()」，任何被"抢走"的消息都会以 {@code error=unknown}
     * 进 DLQ——因为这条消息在本实例从未失败过，lastErrors 里没有它的失败原因。
     * 当两个实例共用同一消费端 id 时（见 {@link #INSTANCE_ID}），处理慢的一方
     * 超过 claim-idle-ms 后，另一方会把它抢过来并直接送进 DLQ，
     * 大量正常消息被误判成毒消息。
     *
     * <p>现在只有「本实例确实处理失败过」才可能进 DLQ，{@code error} 字段必然
     * 携带真实失败原因，不再出现 unknown。
     *
     * <p>尝试次数标准：{@code p.getRedeliveryCount()} 是重领前的投递次数，
     * XCLAIM 使其 +1，故当前这次是第 {@code count+1} 次尝试；失败且已达上限才转 DLQ。
     */
    private void retryOrDlq(CdcProperties.Source source, PendingMessage p) {
        String messageId = p.getId();
        long attempt = p.getRedeliveryCount() + 1;
        try {
            // min-idle-time 用配置值做服务端原子校验：从 XPENDING 读到 XCLAIM 之间
            // 消息可能刚被别的实例处理过（idle 已重置），传 0 会无条件抢回、重复推进
            // 投递次数（多实例下互相抢消息的残留弱化版）
            List<StreamMessage<String, String>> claimed = redis.xclaim(
                    source.stream(), source.group(), consumerId(source), props.claimIdleMs(), messageId);
            if (claimed.isEmpty()) {
                // 消息已被 XDEL/裁掉（Redis 顺带清了 PEL 条目）：清失败留痕防慢性泄漏
                lastErrors.remove(messageId);
            }
            for (StreamMessage<String, String> message : claimed) {
                log.info("CDC 重试投递 stream={} id={} attempt={}/{}",
                        source.stream(), messageId, attempt, props.maxDeliveries());
                if (process(message, source)) {
                    redis.xack(source.stream(), source.group(), messageId);
                    log.info("CDC 重试成功，已确认 stream={} id={}", source.stream(), messageId);
                } else if (attempt >= props.maxDeliveries()) {
                    // 只有"本实例确实处理失败"才会走到这里，lastErrors 必有该 id 的真实原因
                    moveToDlq(source, p, attempt);
                } else {
                    // 仍有重试机会：留在 PEL（XCLAIM 已使投递次数 +1），等下一轮 reclaim
                    log.warn("CDC 重试失败，留在 PEL 等待下一轮 stream={} id={} attempt={}/{}",
                            source.stream(), messageId, attempt, props.maxDeliveries());
                }
            }
        } catch (Exception e) {
            log.warn("CDC 重试异常 stream={} id={}: {}", source.stream(), messageId, e.getMessage(), e);
        }
    }

    /**
     * 超过最大投递次数且本实例确实处理失败：整条转 DLQ（{stream}:dlq，字段式），再 XACK 移出 PEL。
     *
     * <p><b>为什么连诊断信息一起存</b>：人工重放时需要知道"这条为什么失败"
     * （ns/entity/投递次数/失败时间/错误原因），只存原始载荷的话，
     * 排查的人还得去翻历史日志对消息 id，成本极高。
     *
     * @param deliveryCount 实际投递次数（含刚失败的这一次），由调用方传入——
     *                      不要用 {@code p.getRedeliveryCount()}，那是重领前的计数，
     *                      不含刚失败的这一次。
     */
    private void moveToDlq(CdcProperties.Source source, PendingMessage p, long deliveryCount) {
        String messageId = p.getId();
        String dlqStream = source.stream() + DLQ_SUFFIX;
        try {
            // 先回读原始消息：PEL 里只有 id 和统计信息，没有 body
            List<StreamMessage<String, String>> found =
                    redis.xrange(source.stream(), messageId, messageId, 1);
            if (found.isEmpty()) {
                // 消息已被 MAXLEN/XDEL 清掉但 PEL 残留：直接确认，避免永久 pending
                log.warn("CDC pending 消息已不在 stream 中，直接确认 stream={} id={}",
                        source.stream(), messageId);
                redis.xack(source.stream(), source.group(), messageId);
                lastErrors.remove(messageId);
                return;
            }
            StreamMessage<String, String> message = found.get(0);
            Map<String, String> body = message.getBody();
            String msgKey = body.isEmpty() ? "" : body.keySet().iterator().next();
            String payload = body.isEmpty() ? "" : body.values().iterator().next();
            String reason = lastErrors.getOrDefault(messageId, "unknown");
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("ns", source.namespace());
            fields.put("entity", source.entity());
            fields.put("originStream", source.stream());
            fields.put("originGroup", source.group());
            fields.put("msgId", messageId);
            fields.put("msgKey", msgKey);
            fields.put("payload", payload);
            fields.put("deliveryCount", String.valueOf(deliveryCount));
            fields.put("failedAt", TS.format(OffsetDateTime.now()));
            fields.put("error", reason);
            redis.xadd(dlqStream, fields);
            redis.xack(source.stream(), source.group(), messageId);
            lastErrors.remove(messageId);
            IrisMetrics.increment("iris.cdc.dlq", "entity", source.entity());
            log.warn("CDC 毒消息已转 DLQ dlq={} originStream={} msgId={} 投递次数={} error={}",
                    dlqStream, source.stream(), messageId, deliveryCount, reason);
        } catch (Exception e) {
            log.error("CDC 毒消息转 DLQ 失败 stream={} id={}", source.stream(), messageId, e);
        }
    }

    /**
     * 解析本实例标识：环境变量 {@code IRIS_INSTANCE_ID} 优先，否则 {@code 主机名-pid}。
     *
     * <p>主机名要清洗并截断：它会进入消费端 id（出现在 Redis 的 CONSUMERS 列表、
     * 日志与监控标签里），保留空格或非 ASCII 只会让排查更难读。
     */
    private static String resolveInstanceId() {
        String explicit = System.getenv("IRIS_INSTANCE_ID");
        if (explicit != null && !explicit.isBlank()) {
            return sanitizeInstanceId(explicit);
        }
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                // 取不到主机名不是致命问题：pid 已足以在同机内区分实例
                host = "host";
            }
        }
        String cleaned = sanitizeInstanceId(host);
        if (cleaned.length() > INSTANCE_ID_HOST_MAX) {
            cleaned = cleaned.substring(0, INSTANCE_ID_HOST_MAX);
        }
        return cleaned + "-" + ProcessHandle.current().pid();
    }

    private static String sanitizeInstanceId(String raw) {
        return raw.trim().replaceAll("[^A-Za-z0-9._\\-]", "_");
    }

    /**
     * 该 source 在本实例上的消费端 id = 配置基名 + 实例标识。
     *
     * <p>XREADGROUP 与 XCLAIM 必须都走这里。只改一处会制造更糟的局面：
     * 用 A 名读取、用 B 名重领，等于凭空多出一个幽灵消费者。
     */
    private String consumerId(CdcProperties.Source source) {
        return source.consumer() + "-" + INSTANCE_ID;
    }

    /** 安静休眠：被中断时恢复中断标志并立即返回（不吞掉中断）。 */
    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}

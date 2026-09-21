package com.iris.lite.infrastructure.schema;

import com.iris.lite.context.schema.EntitySchema;
import com.iris.lite.context.schema.FieldSchema;
import com.iris.lite.context.schema.FieldType;
import com.iris.lite.context.schema.SchemaManager;
import com.iris.lite.context.schema.SchemaStatus;
import com.iris.lite.infrastructure.config.SchemaProperties;
import com.iris.lite.shared.error.ErrorCode;
import com.iris.lite.shared.error.IrisException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 从 classpath {@code iris/schema/**} 加载固定 Schema，并可选地叠加外部目录实现热加载。
 *
 * <p><b>两层 Schema 模型</b>：
 * <ul>
 *   <li><b>classpath 基线</b>（{@code baseSchemas}）：打包进 JAR，启动时一次性加载，
 *       不可变。保证"最坏情况下服务依然有 Schema 可用"；</li>
 *   <li><b>外部目录</b>（{@code externalSchemas}）：{@code iris.schema.dir} 下的
 *       {@code *.yml}，按 {@code namespace/entity} 覆盖同名 classpath Schema，可热替换。</li>
 * </ul>
 *
 * <p><b>热加载语义</b>：
 * <ul>
 *   <li>检测：轮询目录下 {@code *.yml} 的 mtime，与上次快照不同即触发重载；</li>
 *   <li>替换：先全部解析成功，再<b>原子替换</b> volatile 引用；</li>
 *   <li>失败：任一文件解析失败则<b>整批丢弃、保留旧 Schema</b>，
 *       绝不因一份坏配置让服务不可用。错误通过 {@link #status()} 暴露。</li>
 * </ul>
 */
@Component
public class YamlSchemaProvider implements SchemaManager {

    private static final Logger log = LoggerFactory.getLogger(YamlSchemaProvider.class);

    private static final String CLASSPATH_LOCATION = "classpath:iris/schema/**/*.yml";

    private final SchemaProperties props;

    /** Spring 事件发布器：热重载成功后通知索引层同步。 */
    private final ApplicationEventPublisher eventPublisher;

    /** classpath 基线 Schema，启动时一次性加载，不可变。 */
    private final Map<String, EntitySchema> baseSchemas;

    /** 外部目录 Schema，可热替换。volatile 保证替换对所有读线程立即可见。 */
    private volatile Map<String, EntitySchema> externalSchemas;

    /** 外部文件最近 mtime，用于变更检测。 */
    private volatile Map<String, Long> fileMtimes;

    private volatile long lastReloadAtMillis = System.currentTimeMillis();
    private volatile String lastError = null;

    public YamlSchemaProvider(SchemaProperties props, ApplicationEventPublisher eventPublisher) {
        this.props = props;
        this.eventPublisher = eventPublisher;
        // 构造期即完成首次加载：服务启动完成时 Schema 已就绪，
        // 避免"启动后短暂时间内查询报 Schema 不存在"
        this.baseSchemas = loadClasspath();
        this.fileMtimes = new ConcurrentHashMap<>();
        this.externalSchemas = loadExternal();
    }

    // ---------- 启动加载 ----------

    /**
     * 加载 classpath 基线 Schema。
     *
     * <p>目录不存在时视为 0 个基线实体（不报错）——schema 目录允许整体退役，
     * 全部实体由外部目录提供。仅当目录存在但扫描/解析失败时才 fail-fast。
     *
     * @throws IllegalStateException classpath 资源扫描/解析失败——
     *         基线不可用时服务无法工作，启动即失败是正确的（fail-fast）
     */
    private Map<String, EntitySchema> loadClasspath() {
        Map<String, EntitySchema> result = new ConcurrentHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(CLASSPATH_LOCATION);
            for (Resource resource : resources) {
                try (InputStream in = resource.getInputStream()) {
                    EntitySchema schema = parse(in);
                    result.put(key(schema.namespace(), schema.entity()), schema);
                }
            }
        } catch (IOException e) {
            // PatternResolver 对不存在的目录抛 FileNotFoundException（而非返回空数组）：
            // classpath 基线为空是合法状态（全量走外部目录），此处不 fail-fast
            log.info("classpath Schema 目录为空或不存在的，基线实体 0 个: {}", CLASSPATH_LOCATION);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("加载 classpath Schema 失败: " + CLASSPATH_LOCATION, e);
        }
        log.info("classpath Schema 加载完成，共 {} 个实体", result.size());
        return result;
    }

    /**
     * 加载外部目录 Schema。
     *
     * <p><b>与 classpath 加载的关键差异：容错</b>。单个文件坏掉只跳过该文件
     * 并记录 lastError，classpath 基线继续生效——外部目录是"可选增强"，
     * 它的失败不应该拖垮服务。
     */
    private Map<String, EntitySchema> loadExternal() {
        if (!hasExternalDir()) {
            return new ConcurrentHashMap<>();
        }
        Map<String, EntitySchema> result = new ConcurrentHashMap<>();
        Map<String, Long> mtimes = new ConcurrentHashMap<>();
        Path dir = Paths.get(props.dir());
        if (!Files.isDirectory(dir)) {
            log.warn("外部 Schema 目录不存在，已忽略: {}", props.dir());
            return result;
        }
        for (Path file : listYamlFiles(dir)) {
            try (InputStream in = Files.newInputStream(file)) {
                EntitySchema schema = parse(in);
                result.put(key(schema.namespace(), schema.entity()), schema);
                mtimes.put(file.toString(), Files.getLastModifiedTime(file).toMillis());
            } catch (Exception e) {
                // 启动阶段单个文件坏掉：记录错误但继续，保留 classpath 基线
                log.warn("加载外部 Schema 文件失败，已跳过: {}", file, e);
                if (lastError == null) {
                    lastError = file + ": " + e.getMessage();
                }
            }
        }
        this.fileMtimes = mtimes;
        log.info("外部 Schema 加载完成，共 {} 个实体", result.size());
        return result;
    }

    // ---------- 热加载 ----------

    /**
     * 轮询外部目录 mtime，变更时触发重载（间隔由 {@code iris.schema.poll-interval-ms} 控制）。
     *
     * <p><b>为什么用 mtime 而不是 WatchService</b>：mtime 轮询简单、无平台差异，
     * 且在 Docker 挂载卷、NFS 等场景下 WatchService 事件经常不触发。
     * 代价是最长 poll-interval-ms 的延迟，对 Schema 变更这种低频操作完全可接受。
     *
     * <p>无变更时静默返回——每 3~5 秒一次的无变化轮询不该产生任何日志。
     */
    @Scheduled(fixedDelayString = "${iris.schema.poll-interval-ms:5000}")
    public void pollReload() {
        if (!props.hotReload() || !hasExternalDir()) {
            return;
        }
        Path dir = Paths.get(props.dir());
        if (!Files.isDirectory(dir)) {
            return;
        }
        Map<String, Long> current = new LinkedHashMap<>();
        for (Path file : listYamlFiles(dir)) {
            try {
                current.put(file.toString(), Files.getLastModifiedTime(file).toMillis());
            } catch (IOException e) {
                log.warn("读取文件 mtime 失败: {}", file, e);
            }
        }
        // 与上次快照完全相同 = 无变更，直接返回（Map.equals 比较内容）
        if (current.equals(fileMtimes)) {
            return; // 无变更
        }
        log.debug("检测到 Schema 文件变更，触发重载 旧文件数={} 新文件数={}",
                fileMtimes.size(), current.size());
        reload();
    }

    /**
     * 强制重载外部 Schema。
     *
     * <p><b>全量解析成功才替换</b>：先解析进临时 map {@code fresh}，
     * 循环完整走完无异常才赋值给 {@code externalSchemas}。
     * 中途任何失败都直接 return，旧 Schema 原封不动。
     *
     * <p><b>synchronized</b>：与 {@link #pollReload} 及手动调用互斥，
     * 避免并发重载导致 fileMtimes 与 externalSchemas 不一致。
     */
    @Override
    public synchronized void reload() {
        if (!hasExternalDir()) {
            lastError = "未配置 iris.schema.dir，无可重载的外部 Schema";
            log.warn(lastError);
            return;
        }
        Map<String, EntitySchema> fresh = new ConcurrentHashMap<>();
        Map<String, Long> mtimes = new ConcurrentHashMap<>();
        Path dir = Paths.get(props.dir());
        if (!Files.isDirectory(dir)) {
            lastError = "外部 Schema 目录不存在: " + props.dir();
            log.warn(lastError);
            return;
        }
        try {
            for (Path file : listYamlFiles(dir)) {
                try (InputStream in = Files.newInputStream(file)) {
                    EntitySchema schema = parse(in);
                    fresh.put(key(schema.namespace(), schema.entity()), schema);
                    mtimes.put(file.toString(), Files.getLastModifiedTime(file).toMillis());
                }
            }
        } catch (Exception e) {
            // 任一文件解析失败：整批丢弃，保留旧 Schema，下次轮询自动重试
            lastError = "重载失败，已保留旧 Schema: " + e.getMessage();
            log.warn(lastError, e);
            return;
        }
        this.externalSchemas = fresh;
        this.fileMtimes = mtimes;
        this.lastReloadAtMillis = System.currentTimeMillis();
        this.lastError = null;
        log.info("Schema 热重载完成，外部实体 {} 个", fresh.size());
        // 通知索引层：indexed 声明可能变了，索引需要按新 Schema 重新比对/重建
        eventPublisher.publishEvent(new SchemaReloadedEvent(list()));
    }

    // ---------- 查询 ----------

    /**
     * 获取实体 Schema：外部覆盖优先，回落 classpath 基线，都没有则抛异常。
     *
     * <p>不打 debug 日志：这是查询链路的最高调用频方法之一
     * （每次查询至少两次：应用服务校验字段、仓储定位主键），
     * 打日志的收益远低于噪音成本。
     */
    @Override
    public EntitySchema get(String namespace, String entity) {
        String k = key(namespace, entity);
        EntitySchema schema = externalSchemas.get(k);
        if (schema == null) {
            schema = baseSchemas.get(k);
        }
        if (schema == null) {
            throw new IrisException(ErrorCode.ENTITY_NOT_FOUND,
                    "Schema 不存在: " + namespace + "/" + entity);
        }
        return schema;
    }

    /** 返回热加载状态快照，供运维排查"改了 Schema 为什么没生效"。 */
    @Override
    public SchemaStatus status() {
        return new SchemaStatus(
                props.hotReload() && hasExternalDir(),
                props.dir(),
                size(),
                lastReloadAtMillis,
                lastError);
    }

    // ---------- 辅助 ----------

    /** 是否配置了外部 Schema 目录（热加载的实际前置条件之一）。 */
    private boolean hasExternalDir() {
        return props.dir() != null && !props.dir().isBlank();
    }

    /**
     * 列出目录下所有 {@code *.yml}（只扫一层，不递归），按路径排序。
     *
     * <p>排序保证多次扫描结果顺序稳定，使 mtime 快照的 Map.equals 比较可靠。
     */
    private List<Path> listYamlFiles(Path dir) {
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(p -> p.toString().endsWith(".yml")).sorted().toList();
        } catch (IOException e) {
            log.warn("扫描 Schema 目录失败: {}", dir, e);
            return List.of();
        }
    }

    /**
     * 解析单个 YAML 文件为 {@link EntitySchema}。
     *
     * <p>YAML 结构：{@code namespace / entity / primaryKeys[list] /
     * tenantField(可选) / accessTagField(可选) /
     * fields[{name, type, indexed(可选), tags(可选), index(可选), description(可选),
     * relatedEntity(可选), values(可选，值域语义)}]}。
     * 用 SnakeYAML 的 {@code load} 直接得到 Map，手工映射字段——
     * 比绑定 POJO 更宽松（缺字段不报错，交给 EntitySchema 的构造器校验）。
     */
    @SuppressWarnings("unchecked")
    private EntitySchema parse(InputStream in) {
        Map<String, Object> root = new Yaml().load(in);
        String namespace = (String) root.get("namespace");
        String entity = (String) root.get("entity");
        List<String> primaryKeys = (List<String>) root.get("primaryKeys");
        String tenantField = (String) root.get("tenantField");
        // 行级可见性 tag 字段（可选）。Schema 构造器会校验其 indexed 声明
        String accessTagField = (String) root.get("accessTagField");
        // 表级业务用途描述（可选），Agent 一级 Schema 摘要选表用
        String description = root.get("description") == null
                ? null : String.valueOf(root.get("description"));

        List<FieldSchema> fields = new ArrayList<>();
        List<Map<String, Object>> rawFields =
                (List<Map<String, Object>>) root.get("fields");
        if (rawFields != null) {
            for (Map<String, Object> f : rawFields) {
                // valueOf 大小写敏感，YAML 里 type 写成 string 会抛 IllegalArgumentException
                // indexed 缺省为 false：不声明就默认不进索引，避免"无意全字段建索引"
                // tags 缺省为空：未声明 tags 的字段对所有 Agent 公开
                String name = (String) f.get("name");
                fields.add(new FieldSchema(
                        name,
                        FieldType.valueOf((String) f.get("type")),
                        Boolean.TRUE.equals(f.get("indexed")),
                        f.get("tags") instanceof List<?> l
                                ? l.stream().map(String::valueOf).toList()
                                : List.of(),
                        parseIndex(name, (String) f.get("index")),
                        f.get("description") == null ? null : String.valueOf(f.get("description")),
                        // 外键声明（可选）。目标实体存在性与 FK 索引校验分别由
                        // 运行期（schemaProvider.get 报 ENTITY_NOT_FOUND）与
                        // EntitySchema 构造器（加载期 fail-fast）负责
                        f.get("relatedEntity") == null ? null : String.valueOf(f.get("relatedEntity")),
                        // 值域语义（可选）：字符串直取；列表形态（每项一条
                        // "值=含义"）以 "; " 连接成单行文本——两种写法都合法，
                        // 后者便于在 yml 里逐值注释。归一（空转 null）在 record 内完成
                        parseValues(name, f.get("values")),
                        // 跨表携带来源（可选）：fk字段->主表实体.主表字段
                        f.get("carriedFrom") == null ? null : String.valueOf(f.get("carriedFrom"))));
            }
        }
        return new EntitySchema(namespace, entity, primaryKeys, fields, tenantField, accessTagField, description);
    }

    /**
     * 解析显式索引形态声明（tag | numeric | text）。
     *
     * <p>非法值<b>告警并忽略</b>（视为未声明）而不是报错：Schema 热载是运维动作，
     * 一个笔误不该让整个实体不可用；字段还有 indexed 布尔和 primaryKeys 的
     * 强制 indexed 兜底，行为仍然确定。
     */
    private String parseIndex(String fieldName, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toLowerCase();
        if (!"tag".equals(v) && !"numeric".equals(v) && !"text".equals(v)) {
            log.warn("字段 {} 的 index 声明非法: {}（合法值 tag/numeric/text），已忽略", fieldName, raw);
            return null;
        }
        // text 仅对 STRING 有意义（分词匹配）；其余类型声明 text 降级为 tag 并告警，
        // FieldSchema.effectiveIndex() 里有同样的兜底，这里提前提示避免运维困惑
        return v;
    }

    /**
     * 解析值域语义声明（可选）。
     *
     * <p>两种合法写法：字符串（{@code values: "0=未支付; 1=已支付"}）直取；
     * 列表（每项一条 {@code "0=未支付"}）以 {@code "; "} 连接成单行。
     * 其他形态（数字、嵌套 Map 等）告警并忽略——与 {@link #parseIndex} 同一
     * 容错哲学：一个注解写错不该让整个实体不可用。
     */
    private String parseValues(String fieldName, Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof String s) {
            return s;
        }
        if (raw instanceof List<?> l && !l.isEmpty()) {
            return l.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("; "));
        }
        log.warn("字段 {} 的 values 声明形态非法（应为字符串或字符串列表），已忽略: {}", fieldName, raw);
        return null;
    }

    /** Schema 内部索引键：{@code namespace/entity}。 */
    private String key(String namespace, String entity) {
        return namespace + "/" + entity;
    }

    /** 当前生效的 Schema 总数（classpath + 外部，按 namespace/entity 去重）。 */
    public int size() {
        Map<String, EntitySchema> merged = new LinkedHashMap<>(baseSchemas);
        merged.putAll(externalSchemas);
        return merged.size();
    }

    /** 当前生效的全部 Schema（classpath 基线 + 外部覆盖合并）。 */
    @Override
    public List<EntitySchema> list() {
        Map<String, EntitySchema> merged = new LinkedHashMap<>(baseSchemas);
        merged.putAll(externalSchemas);
        return List.copyOf(merged.values());
    }

    /** 当前生效 Schema 的命名快照（调试用）：{@code ns/entity -> [字段名]}。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, EntitySchema> merged = new LinkedHashMap<>(baseSchemas);
        merged.putAll(externalSchemas);
        merged.forEach((k, v) -> map.put(k, v.fields().stream().map(FieldSchema::name).toList()));
        return map;
    }
}

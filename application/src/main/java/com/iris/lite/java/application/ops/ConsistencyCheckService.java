package com.iris.lite.java.application.ops;

import com.iris.lite.java.context.model.EntityProjection;
import com.iris.lite.java.context.model.ProjectionPage;
import com.iris.lite.java.context.model.QueryRequest;
import com.iris.lite.java.context.repository.EntityProjectionRepository;
import com.iris.lite.java.context.repository.SourceTableReader;
import com.iris.lite.java.context.schema.EntitySchema;
import com.iris.lite.java.context.schema.SchemaProvider;
import com.iris.lite.java.shared.error.ErrorCode;
import com.iris.lite.java.shared.error.IrisException;
import com.iris.lite.java.shared.key.KeyStrategy;
import com.iris.lite.java.shared.model.EntityKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 源库-投影一致性校验与修复。
 *
 * <p><b>背景</b>：CDC 链路（Debezium → Redis Stream → 消费投影）在理论上保证
 * 最终一致，但实践中存在漂移窗口：消费中断时的删除事件丢失、DLQ 消息未重放、
 * 手工误删投影 key 等。本服务把不一致核查变成一个可触发的管理端点，
 * 替代人工逐条比对。
 *
 * <p><b>比对逻辑</b>：以源库为准（source of truth）：
 * <ul>
 *   <li>{@code count} 档：源表 COUNT vs 投影 total（FT.SEARCH 匹配数，O(1)）——
 *       快速判断"有没有明显漂移"，大表友好；</li>
 *   <li>{@code full} 档：源表全量 vs 投影全量逐行比字段——产出三类差异：
 *       missing（源有投影无）、orphan（投影有源无）、mismatched（都在但字段值不同）。</li>
 * </ul>
 *
 * <p><b>修复语义</b>：以源库为准——missing/mismatched 用源行 upsert 覆盖，
 * orphan 删除投影。修复只动投影，绝不动源库（投影是源库的镜像，方向不可逆）。
 *
 * <p><b>投影全量遍历方式</b>：沿查询链路翻页（FT.SEARCH + LIMIT 下推），
 * pageSize 取 QueryRequest 上限 200；不做全量 SCAN——分页下推是性能红线，
 * 校验工具同样要守。
 */
@Service
public class ConsistencyCheckService {

    private static final Logger log = LoggerFactory.getLogger(ConsistencyCheckService.class);

    /** 投影翻页大小：QueryRequest 紧凑构造器允许的最大值。 */
    private static final int PAGE_SIZE = 200;

    private final SchemaProvider schemaProvider;
    private final EntityProjectionRepository repository;
    private final ObjectProvider<SourceTableReader> sourceReaderProvider;

    public ConsistencyCheckService(
            SchemaProvider schemaProvider,
            EntityProjectionRepository repository,
            ObjectProvider<SourceTableReader> sourceReaderProvider) {
        this.schemaProvider = schemaProvider;
        this.repository = repository;
        this.sourceReaderProvider = sourceReaderProvider;
    }

    /** 校验请求参数。 */
    public record CheckRequest(String namespace, String entity, String mode, String tableName) {
    }

    /** 校验报告：三类差异 + 计数摘要。 */
    public record ConsistencyReport(
            String namespace, String entity, String mode,
            long sourceCount, long projectionCount,
            List<Map<String, Object>> missing,
            List<Map<String, Object>> orphan,
            List<Map<String, Object>> mismatched) {
    }

    /** 修复结果：三类差异各修了多少。 */
    public record RepairResult(
            String namespace, String entity,
            int repairedMissing, int repairedMismatched, int removedOrphan) {
    }

    /**
     * 执行一致性校验。
     *
     * @param request 含 namespace/entity/mode(count|full)/tableName（缺省用 entity 名）
     */
    public ConsistencyReport check(CheckRequest request) {
        SourceTableReader source = requireSourceReader();
        EntitySchema schema = schemaProvider.get(request.namespace(), request.entity());
        String pk = schema.primaryKeys().get(0);
        String table = request.tableName() == null || request.tableName().isBlank()
                ? request.entity() : request.tableName();
        String mode = request.mode() == null ? "count" : request.mode();

        long sourceCount = source.count(table);
        long projectionCount = projectionTotal(request.namespace(), request.entity());

        if ("count".equals(mode)) {
            log.info("一致性校验(count) ns={} entity={} 源={} 投影={}",
                    request.namespace(), request.entity(), sourceCount, projectionCount);
            return new ConsistencyReport(request.namespace(), request.entity(), mode,
                    sourceCount, projectionCount, List.of(), List.of(), List.of());
        }

        // ---------- full 档：全量逐行比对 ----------
        Map<String, Map<String, Object>> sourceRows = byKey(source.fetchAll(table), pk);
        Map<String, Map<String, Object>> projectionRows = fetchAllProjection(request.namespace(), request.entity());

        List<Map<String, Object>> missing = new ArrayList<>();
        List<Map<String, Object>> orphan = new ArrayList<>();
        List<Map<String, Object>> mismatched = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : sourceRows.entrySet()) {
            Map<String, Object> projRow = projectionRows.get(e.getKey());
            if (projRow == null) {
                missing.add(rowOf(pk, e.getKey(), "源库存在、投影缺失", List.of()));
            } else if (!fieldEquals(e.getValue(), projRow)) {
                mismatched.add(rowOf(pk, e.getKey(), "字段值不一致", diffFields(e.getValue(), projRow)));
            }
        }
        for (String key : projectionRows.keySet()) {
            if (!sourceRows.containsKey(key)) {
                orphan.add(rowOf(pk, key, "投影存在、源库不存在（孤儿投影）", List.of()));
            }
        }
        log.info("一致性校验(full) ns={} entity={} 源={} 投影={} missing={} orphan={} mismatched={}",
                request.namespace(), request.entity(), sourceRows.size(), projectionRows.size(),
                missing.size(), orphan.size(), mismatched.size());
        return new ConsistencyReport(request.namespace(), request.entity(), mode,
                sourceCount, projectionCount, missing, orphan, mismatched);
    }

    /**
     * 以源库为准修复投影：missing/mismatched upsert 源行，orphan 删除。
     *
     * <p>修复即"把源行重新投影一遍"——复用 CDC 消费侧同一份投影写入逻辑
     * （{@link EntityProjectionRepository#upsert}），保证写入格式与正常链路完全一致。
     */
    public RepairResult repair(CheckRequest request) {
        SourceTableReader source = requireSourceReader();
        EntitySchema schema = schemaProvider.get(request.namespace(), request.entity());
        String pk = schema.primaryKeys().get(0);
        String table = request.tableName() == null || request.tableName().isBlank()
                ? request.entity() : request.tableName();

        Map<String, Map<String, Object>> sourceRows = byKey(source.fetchAll(table), pk);
        Map<String, Map<String, Object>> projectionRows =
                fetchAllProjection(request.namespace(), request.entity());

        int repairedMissing = 0;
        int repairedMismatched = 0;
        int removedOrphan = 0;
        for (Map.Entry<String, Map<String, Object>> e : sourceRows.entrySet()) {
            Map<String, Object> projRow = projectionRows.get(e.getKey());
            boolean missing = projRow == null;
            boolean mismatched = projRow != null && !fieldEquals(e.getValue(), projRow);
            if (missing || mismatched) {
                EntityProjection projection = new EntityProjection(
                        new EntityKey(request.namespace(), request.entity(), e.getKey()),
                        normalize(e.getValue()));
                repository.upsert(projection);
                if (missing) {
                    repairedMissing++;
                } else {
                    repairedMismatched++;
                }
            }
        }
        for (String key : projectionRows.keySet()) {
            if (!sourceRows.containsKey(key)) {
                repository.delete(new EntityKey(request.namespace(), request.entity(), key));
                removedOrphan++;
            }
        }
        log.info("一致性修复完成 ns={} entity={} missing={} mismatched={} orphan={}",
                request.namespace(), request.entity(), repairedMissing, repairedMismatched, removedOrphan);
        return new RepairResult(request.namespace(), request.entity(),
                repairedMissing, repairedMismatched, removedOrphan);
    }

    // ---------- 内部辅助 ----------

    private SourceTableReader requireSourceReader() {
        SourceTableReader reader = sourceReaderProvider.getIfAvailable();
        if (reader == null) {
            throw new IrisException(ErrorCode.INTERNAL_ERROR,
                    "源库一致性校验未启用：请配置 iris.consistency.jdbc-url");
        }
        return reader;
    }

    /** 投影匹配总数（FT.SEARCH total O(1)，不下载数据）。 */
    private long projectionTotal(String namespace, String entity) {
        ProjectionPage page = repository.find(new QueryRequest(namespace, entity, null, null, 1, 1, null));
        return page.total();
    }

    /**
     * 投影全量遍历：沿索引翻页（分页下推，遵守性能红线）。
     * 主键直接取投影 key（不依赖 data 里是否存了主键字段）。
     */
    private Map<String, Map<String, Object>> fetchAllProjection(String namespace, String entity) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        int page = 1;
        while (true) {
            ProjectionPage p = repository.find(new QueryRequest(namespace, entity, null, null, page, PAGE_SIZE, null));
            for (EntityProjection projection : p.items()) {
                result.put(projection.key().primaryKey(), projection.data());
            }
            if ((long) page * PAGE_SIZE >= p.total() || p.items().isEmpty()) {
                break;
            }
            page++;
        }
        return result;
    }

    /** 源行列表 -> 主键值 -> 行 的索引。 */
    private Map<String, Map<String, Object>> byKey(List<Map<String, Object>> rows, String pk) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object v = row.get(pk);
            if (v != null) {
                map.put(String.valueOf(v), row);
            }
        }
        return map;
    }

    /**
     * 逐字段比较源行与投影行。
     *
     * <p>全部转字符串比较：JDBC 返回的类型（Timestamp/BigDecimal/Integer）与
     * JSON 往返后的类型（String/Long/Double）形态不同但值相同——
     * 按字符串规范比较能容忍类型形态差异，只抓真正的值漂移。
     * 字符串不等时再做一次数值比较（BigDecimal compareTo）：消除小数尾零与
     * 计数法差异（例如 decimal(6,4) 的 0.0060 vs 投影 0.006、
     * decimal(14,2) 的 500000.00 vs 500000.0，纯字符串比对会全表误报 mismatched）。
     */
    private boolean fieldEquals(Map<String, Object> sourceRow, Map<String, Object> projectionRow) {
        for (Map.Entry<String, Object> e : sourceRow.entrySet()) {
            Object sv = e.getValue();
            Object pv = projectionRow.get(e.getKey());
            String s = sv == null ? null : normalizeScalar(sv);
            String p = pv == null ? null : String.valueOf(pv);
            if (!valuesEqual(s, p)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 标量规范化：datetime 转毫秒 epoch 与 CDC envelope 形态对齐。
     *
     * <p><b>时区标准</b>：Debezium MySQL connector 对无时区的
     * DATETIME 按数据库会话时区解释，投影值为 UTC 标准
     * （如某 DATETIME 值按 UTC 解释得 1788220800000，而非东八区标准 1788192000000）。
     * 这里必须用 UTC 转换，
     * 否则时间列永远差 8 小时、full 校验全表误报 mismatched。
     */
    private String normalizeScalar(Object v) {
        if (v instanceof Timestamp ts) {
            return String.valueOf(ts.getTime());
        }
        if (v instanceof LocalDateTime ldt) {
            return String.valueOf(ldt.toEpochSecond(ZoneOffset.UTC) * 1000L);
        }
        return String.valueOf(v);
    }

    /** 值相等判定：字符串相等优先，不等时回落数值比较（null 只与 null 相等）。 */
    private boolean valuesEqual(String s, String p) {
        if (s == null || p == null) {
            return s == null && p == null;
        }
        if (s.equals(p)) {
            return true;
        }
        try {
            return new BigDecimal(s).compareTo(new BigDecimal(p)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 源行修复写投影前的形态归一化：时间类型转 UTC epoch 毫秒（与 Debezium envelope 一致）。 */
    private Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> out = new HashMap<>();
        row.forEach((k, v) -> {
            if (v instanceof Timestamp ts) {
                out.put(k, ts.getTime());
            } else if (v instanceof LocalDateTime ldt) {
                out.put(k, ldt.toEpochSecond(ZoneOffset.UTC) * 1000L);
            } else {
                out.put(k, v);
            }
        });
        return out;
    }

    /** 差异行摘要（不暴露整行数据，报告更紧凑）；mismatched 额外带差异字段明细。 */
    private Map<String, Object> rowOf(String pk, String pkValue, String reason, List<String> fields) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(pk, pkValue);
        row.put("reason", reason);
        if (!fields.isEmpty()) {
            row.put("fields", fields);
        }
        return row;
    }

    /** 找出值不一致的字段名（配合 fieldEquals 使用，便于报告定位）。 */
    private List<String> diffFields(Map<String, Object> sourceRow, Map<String, Object> projectionRow) {
        List<String> diff = new ArrayList<>();
        for (Map.Entry<String, Object> e : sourceRow.entrySet()) {
            Object sv = e.getValue();
            Object pv = projectionRow.get(e.getKey());
            String s = sv == null ? null : normalizeScalar(sv);
            String p = pv == null ? null : String.valueOf(pv);
            if (!valuesEqual(s, p)) {
                diff.add(e.getKey() + "(源=" + s + ", 投影=" + p + ")");
            }
        }
        return diff;
    }
}

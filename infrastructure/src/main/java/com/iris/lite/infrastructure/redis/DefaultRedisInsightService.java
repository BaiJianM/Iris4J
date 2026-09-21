package com.iris.lite.infrastructure.redis;

import com.iris.lite.application.ops.RedisInsightService;
import com.iris.lite.shared.error.ErrorCode;
import com.iris.lite.shared.error.IrisException;
import com.iris.lite.shared.key.KeyStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link RedisInsightService} 实现：纯观测命令组合（SCAN/TYPE/PTTL/INFO/FT.INFO）。
 *
 * <p><b>只读边界</b>：全部走 RedisAdapter 的读命令，无任何写路径——
 * 键值编辑会绕过治理链并制造 CDC 投影漂移，控制台明确不做。
 */
@Service
public class DefaultRedisInsightService implements RedisInsightService {

    private static final Logger log = LoggerFactory.getLogger(DefaultRedisInsightService.class);

    /** 单键视图兜底错误提示。 */
    private static final String VIEW_FAIL = "读取失败";

    private final RedisAdapter redis;
    private final KeyStrategy keys;

    public DefaultRedisInsightService(RedisAdapter redis, KeyStrategy keys) {
        this.redis = redis;
        this.keys = keys;
    }

    @Override
    public KeyPage scanKeys(String pattern, int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        RedisAdapter.ScanPage page = redis.scanKeys(pattern, bounded);
        // SCAN 结果排序后返回：SCAN 本身无序，控制台需要稳定展示（同 pattern 两次结果可对比）
        List<String> sorted = new ArrayList<>(page.keys());
        sorted.sort(String::compareTo);
        return new KeyPage(sorted, page.truncated());
    }

    @Override
    public KeyValueView viewKey(String key) {
        String type;
        try {
            type = redis.type(key);
        } catch (Exception e) {
            log.warn("键类型读取失败 key={} - {}", key, e.getMessage(), e);
            return new KeyValueView(key, null, null, null, null, null, "TYPE 失败: " + e.getMessage());
        }
        Long pttl = null;
        try {
            long v = redis.pttl(key);
            pttl = v < 0 ? null : v; // -1 无 TTL / -2 不存在 → 不展示 TTL
        } catch (Exception ignored) {
            // PTTL 失败不影响负载展示
        }
        String json = null;
        Map<String, String> hash = null;
        String string = null;
        String error = null;
        try {
            switch (type) {
                // Redis 的 TYPE 对 JSON 文档返回 "ReJSON-RL"（不是 "json"）——两个名都接住，
                // 否则控制台单键视图对全部 JSON 文档只显示"该类型暂不展开负载"
                case "json", "ReJSON-RL" -> json = redis.jsonGet(key);
                case "hash" -> {
                    hash = redis.hgetall(key);
                    decodeVecField(key, hash);
                }
                case "string" -> string = redis.get(key);
                default -> {
                    // stream/zset/set/list：控制台首期只展示类型与 TTL，不展开负载
                }
            }
        } catch (Exception e) {
            error = VIEW_FAIL + ": " + e.getMessage();
            log.warn("键负载读取失败 key={} type={} - {}", key, type, e.getMessage(), e);
        }
        return new KeyValueView(key, type, pttl, json, hash, string, error);
    }

    /**
     * 把 hash 中的二进制向量字段（vec）替换为可读摘要（控制台"向量可视化"）。
     *
     * <p>vec 存的是 float32 小端<b>原始字节</b>（{@code Vectors.toFloat32Bytes}，
     * KNN 向量索引的标准存法），hgetall 的文本解码下必然是乱码——这不是展示 bug，
     * 是协议设计如此。检测到 UTF-8 替换符 U+FFFD 即判定为二进制，按与写入对称的
     * little-endian float32 解码出 维度/前几维采样/L2 范数，让运维能"看懂"向量。
     */
    private void decodeVecField(String key, Map<String, String> hash) {
        String vec = hash.get("vec");
        if (vec == null || vec.indexOf('\uFFFD') < 0) {
            return; // 可读文本或无 vec 字段：不动
        }
        try {
            byte[] raw = redis.hgetBinary(key, "vec");
            if (raw == null || raw.length < 4 || raw.length % 4 != 0) {
                hash.put("vec", "(二进制值 " + (raw == null ? 0 : raw.length)
                        + " 字节，无法按 float32 解码)");
                return;
            }
            int dim = raw.length / 4;
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int preview = Math.min(5, dim);
            double sq = 0;
            StringBuilder sb = new StringBuilder()
                    .append("float32[").append(dim).append("] 前")
                    .append(preview).append("维 [");
            for (int i = 0; i < dim; i++) {
                float v = bb.getFloat();
                sq += (double) v * v;
                if (i < preview) {
                    sb.append(i > 0 ? ", " : "").append(String.format("%.4f", v));
                }
            }
            sb.append("…] · L2范数 ").append(String.format("%.4f", Math.sqrt(sq)))
                    .append(" · ").append(raw.length)
                    .append(" 字节二进制（KNN 向量索引）");
            hash.put("vec", sb.toString());
        } catch (Exception e) {
            log.debug("向量字段摘要失败 key={} - {}", key, e.getMessage(), e);
            hash.put("vec", "(二进制向量，摘要生成失败)");
        }
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, String> clients = redis.info("clients");
        Map<String, String> stats = redis.info("stats");
        Map<String, Object> out = new LinkedHashMap<>();
        // 选取关键字段（缺失按 null）：字段名与 Redis INFO 保持一致，UI 直接展示
        pick(out, clients, "connected_clients");
        pick(out, clients, "blocked_clients");
        pick(out, clients, "maxclients");
        pick(out, stats, "total_commands_processed");
        pick(out, stats, "instantaneous_ops_per_sec");
        pick(out, stats, "keyspace_hits");
        pick(out, stats, "keyspace_misses");
        pick(out, stats, "evicted_keys");
        pick(out, stats, "expired_keys");
        pick(out, stats, "total_net_input_bytes");
        pick(out, stats, "total_net_output_bytes");
        pick(out, stats, "rejected_connections");
        // 命中率现算（分母 0 → null，区分"无数据"与"全未命中"）
        long hits = parseLong(out.get("keyspace_hits"));
        long misses = parseLong(out.get("keyspace_misses"));
        long total = hits + misses;
        out.put("keyspace_hit_rate", total == 0 ? null : (double) hits / total);
        return out;
    }

    @Override
    public Map<String, Object> indexInfo(String namespace, String entity) {
        String index = keys.indexKey(namespace, entity);
        RedisAdapter.FtIndexInfo info;
        try {
            info = redis.ftInfo(index);
        } catch (Exception e) {
            throw new IrisException(ErrorCode.ENTITY_NOT_FOUND, "索引不存在或不可用: " + index);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index", index);
        out.put("numDocs", info.numDocs());
        // 字段别名→类型（TAG/NUMERIC/TEXT/VECTOR...），LinkedHashMap 保持 FT.INFO 顺序
        out.put("fields", new LinkedHashMap<>(info.fieldTypes()));
        return out;
    }

    private static void pick(Map<String, Object> out, Map<String, String> source, String field) {
        String v = source.get(field);
        if (v != null) {
            try {
                out.put(field, Long.parseLong(v));
                return;
            } catch (NumberFormatException ignored) {
                // 非数值（理论不存在）按字符串保留
            }
            out.put(field, v);
        } else {
            out.put(field, null);
        }
    }

    private static long parseLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }
}

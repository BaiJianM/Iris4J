package com.iris4j.application.ops;

import java.util.List;
import java.util.Map;

/**
 * Redis 观测读面（管理控制台）：键浏览、连接/吞吐统计、FT 索引详情。
 *
 * <p><b>只读红线</b>：本接口全部为观测操作（SCAN/TYPE/PTTL/INFO/FT.INFO），
 * 不提供任何写路径——键值编辑会绕过治理链并制造 CDC 投影漂移，
 * 控制台明确不做。
 */
public interface RedisInsightService {

    /** 有界键扫描结果：keys + 是否还有更多。 */
    record KeyPage(List<String> keys, boolean truncated) {
    }

    /** 单键只读视图：按类型填充对应负载，读不到的字段保持 null。 */
    record KeyValueView(
            String key,
            String type,
            Long pttlMs,
            String json,
            Map<String, String> hash,
            String string,
            String error) {
    }

    /**
     * 有界键扫描。
     *
     * @param pattern glob 模式（如 {@code iris:demo:memory:*}）
     * @param limit   返回上限（1-500）
     */
    KeyPage scanKeys(String pattern, int limit);

    /** 单键只读视图（TYPE → 按类型读取 JSON/hash/string 负载 + PTTL）。 */
    KeyValueView viewKey(String key);

    /**
     * 连接与吞吐统计：INFO clients + INFO stats 的合并投影（选中关键字段）。
     * 返回的 map 直接序列化为 JSON，字段名与 Redis INFO 保持一致。
     */
    Map<String, Object> stats();

    /**
     * FT 索引详情（FT.INFO 的 num_docs + 字段别名→类型映射）。
     *
     * @throws com.iris4j.shared.error.IrisException 索引不存在（NOT_FOUND）
     */
    Map<String, Object> indexInfo(String namespace, String entity);
}

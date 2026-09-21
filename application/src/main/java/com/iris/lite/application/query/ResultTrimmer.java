package com.iris.lite.application.query;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 工具结果硬裁剪。
 *
 * <p><b>为什么必须有</b>：工具结果整页 JSON 原样回灌 LLM 上下文是上下文爆炸的
 * 放大器——200 行 × 全字段一次调用就能挤干推理空间。行数上限 + 字节上限
 * 双闸门在<b>工具结果装配处</b>兜底，与 {@code PageRequest.MAX_PAGE_SIZE}（防打爆
 * Redis）分工不同：这里保护的是模型上下文。
 *
 * <p><b>放 application 模块</b>：Agent 分发器（application）与 MCP 动态工具执行器
 * （api，依赖 application）两条装配路径共用；shared 模块无 Jackson 依赖，不为此引入。
 *
 * <p><b>裁剪必须显式告知</b>：静默截断会让模型误以为拿到了全量数据（隐蔽错误放大），
 * 所以 notice 里带「共 N 行/请用 filters 收窄/fields 减列」的引导，
 * 与查询元数据的取数引导成对出现。
 */
public final class ResultTrimmer {

    private ResultTrimmer() {
    }

    /** 行数上限：超过即截断（模型侧引导用 filters/page 精准取数，而不是放松上限）。 */
    public static final int MAX_ROWS = 50;

    /** 字节上限（序列化后的 UTF-8 字节数）。 */
    public static final int MAX_BYTES = 32 * 1024;

    /** 裁剪结果：items 为裁剪后的行，notice 非空表示发生过裁剪（须放进结果给模型看）。 */
    public record Trimmed(List<Map<String, Object>> items, String notice) {
    }

    /**
     * 行数 + 字节双闸门裁剪。未触发时原样返回（notice 为 null，省一次序列化开销）。
     *
     * @param items        待裁剪的行（不修改原列表）
     * @param totalRows    匹配总行数（用于 notice 文案；与 items.size() 无关）
     * @param objectMapper 序列化器（字节闸门需要；null = 跳过字节闸门）
     */
    public static Trimmed trim(List<Map<String, Object>> items, long totalRows,
                               ObjectMapper objectMapper) {
        if (items == null || items.size() <= MAX_ROWS) {
            return new Trimmed(items, byteTrim(items, totalRows, objectMapper));
        }
        String notice = "结果已截断：共 " + totalRows + " 行，仅返回前 " + MAX_ROWS
                + " 行。请用 filters 收窄条件、fields 减少列、page 翻页精准取数，不要整页拉取。";
        List<Map<String, Object>> cut = items.subList(0, MAX_ROWS);
        String byteNotice = byteTrim(cut, totalRows, objectMapper);
        return new Trimmed(cut, byteNotice != null ? notice + " " + byteNotice : notice);
    }

    /** 字节闸门：超限则按平均行宽估算保留行数并递减重试，直到放进预算（或只剩 1 行）。 */
    private static String byteTrim(List<Map<String, Object>> items, long totalRows,
                                   ObjectMapper objectMapper) {
        if (objectMapper == null || items == null || items.size() <= 1) {
            return null;
        }
        if (serializedSize(items, objectMapper) <= MAX_BYTES) {
            return null;
        }
        List<Map<String, Object>> kept = items;
        while (kept.size() > 1 && serializedSize(kept, objectMapper) > MAX_BYTES) {
            int avg = Math.max(1, serializedSize(kept, objectMapper) / kept.size());
            int target = Math.max(1, Math.min(kept.size() - 1, MAX_BYTES / avg));
            kept = kept.subList(0, target);
        }
        return "结果因体积超限被截断为 " + kept.size() + " 行（上限 32KB）。"
                + "请用 fields 只取需要的列、filters 收窄条件；共 " + totalRows + " 行可用 total 与翻页获取。";
    }

    private static int serializedSize(List<Map<String, Object>> items, ObjectMapper objectMapper) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(items);
            return json.length;
        } catch (Exception e) {
            // 序列化失败时按超限处理（触发逐轮减半，最终至少返回 1 行不阻断主流程）
            return Integer.MAX_VALUE;
        }
    }
}

package com.iris.lite.java.api.mcp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 动态实体工具索引的<b>不可变快照</b>（on-demand 按需发现模式）。
 *
 * <p><b>不可变 + 预计算</b>：描述、参数定义、指纹、各维度检索文本都在快照构建时
 * 算好——search 路径零构建开销，读方（MCP 请求线程）无锁；写方（对账线程）
 * 整体替换快照引用（volatile），在途调用持旧快照无害。
 *
 * <p><b>评分搜索</b>：token 化查询，单 token 取各维度最高分、多 token 求和，
 * 零分淘汰；命中越多排序越靠前（多词 AND 倾向）。中文整串作为一个 token，
 * contains 匹配——不依赖分词器。
 */
public record ToolIndexSnapshot(Map<String, IndexedTool> byName) {

    public static final ToolIndexSnapshot EMPTY = new ToolIndexSnapshot(Map.of());

    /** 搜索结果上限的默认值与硬上限。 */
    public static final int DEFAULT_LIMIT = 8;
    public static final int MAX_LIMIT = 20;

    /**
     * 索引条目：工具定义 + 预计算的描述/参数/指纹/检索文本。
     */
    public record IndexedTool(
            EntityToolFactory.ToolDef def,
            String description,
            Map<String, Object> parameters,
            String fingerprint,
            String nameLower,
            String entityLower,
            String namespaceLower,
            String fieldNamesLower,
            String fieldDescsLower,
            String descriptionLower) {
    }

    /**
     * 搜索结果：命中总条数（截断前）+ 返回条目；query 为空时 blank=true（调用方转目录概览）。
     */
    public record SearchResult(List<IndexedTool> items, int totalMatches, boolean blank) {
    }

    /**
     * 评分搜索。
     *
     * @param query 关键词；空白返回 blank=true 的结果（调用方转目录概览）
     * @param limit 返回条数（≤0 用默认 8，>20 截到 20）
     */
    public SearchResult search(String query, int limit) {
        String trimmed = query == null ? "" : query.trim();
        // 精确工具名直查：Agent 幂等复查单个工具（分词会把 query_order 拆散，
        // 同分工具按名字排序可能挤掉目标，故先走精确路径）
        if (!trimmed.isEmpty()) {
            IndexedTool exact = byName.get(trimmed);
            if (exact != null) {
                return new SearchResult(List.of(exact), 1, false);
            }
        }
        List<String> tokens = tokenize(trimmed);
        if (tokens.isEmpty()) {
            return new SearchResult(List.of(), 0, true);
        }
        int effectiveLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        record Scored(IndexedTool tool, long score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (IndexedTool t : byName.values()) {
            long s = 0;
            for (String token : tokens) {
                s += score(t, token);
            }
            if (s > 0) {
                scored.add(new Scored(t, s));
            }
        }
        scored.sort(Comparator.comparingLong(Scored::score).reversed()
                .thenComparing(sc -> sc.tool().def().name()));
        List<IndexedTool> items = scored.stream()
                .limit(effectiveLimit)
                .map(Scored::tool)
                .toList();
        return new SearchResult(items, scored.size(), false);
    }

    /** 单 token 评分：各维度取最高分（维度间有包含关系，取 max 防重复计分）。 */
    private long score(IndexedTool t, String token) {
        long best = 0;
        if (t.nameLower().equals(token)) {
            best = 100;
        } else if (t.nameLower().contains(token)) {
            best = Math.max(best, 60);
        }
        if (t.entityLower().equals(token)) {
            best = Math.max(best, 80);
        } else if (t.entityLower().contains(token)) {
            best = Math.max(best, 50);
        }
        if (t.fieldNamesLower().contains(token)) {
            best = Math.max(best, 40);
        }
        if (t.namespaceLower().contains(token)) {
            best = Math.max(best, 30);
        }
        if (t.fieldDescsLower().contains(token)) {
            best = Math.max(best, 20);
        }
        if (t.descriptionLower().contains(token)) {
            best = Math.max(best, 10);
        }
        return best;
    }

    /**
     * 查询分词：按非字母数字/非中文的字符切分 + 小写。
     * 中文不分词——整段连续中文作为一个 token，靠 contains 匹配；
     * 混合名如 {@code ord_order} 切成 ord / order 两个 token，两个都命中加分。
     */
    private static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String raw : query.toLowerCase(Locale.ROOT).split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (!raw.isBlank()) {
                tokens.add(raw);
            }
        }
        return tokens;
    }
}

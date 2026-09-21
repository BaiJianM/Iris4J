package com.iris.lite.context.model;

import java.util.List;
import java.util.Map;

/**
 * 聚合结果：{@code totalGroups} 为分组总数（不受 limit 影响，
 * 语义对齐 FT.SEARCH 的 total），{@code rows} 为实际返回的组行（已按 sortBy/limit 截取）。
 *
 * <p><b>维度归并</b>：{@code inputGroups} 为归并前组数——
 * 维度归并时 {@code totalGroups} 是归并后的组数（如 8 大类），{@code inputGroups}
 * 是归并前的原始组数（如 2957 个配置实例），调用方（Agent）需要两个数才能声明口径。
 * 普通聚合两者恒等。
 *
 * <p>行值统一为 JSON 友好类型：数值归约结果转 Double、分组键转 String/Number——
 * 上层直接序列化给 LLM，不再做二次解释。
 */
public record AggregateResult(long totalGroups, long inputGroups, List<Map<String, Object>> rows) {

    /** 空结果（匹配为零或索引回填期拒绝场景的上层兜底）。 */
    public static AggregateResult empty() {
        return new AggregateResult(0, 0, List.of());
    }
}

package com.iris.lite.context.model;

import com.iris.lite.shared.error.ErrorCode;
import com.iris.lite.shared.error.IrisException;

/**
 * 维度归并路径：{@code "fk字段->维表实体.维度字段"}。
 *
 * <p><b>为什么是路径语法而不是独立 translate 参数</b>：中间件定位——归并能力来自
 * 接入方在 schema yml 里声明的 {@code relatedEntity}（关系图谱），声明过关系
 * 即自动可用，任何行业零额外接入成本；路径只是把模型已知的关系写出来。
 * 案例不得渗入本类——它只认「FK 字段 + 关系边 + 维度字段」这个通用结构。
 *
 * <p><b>v1 边界</b>：单跳（一条箭头）；多跳路径（BFS 图谱解析）留 v2。
 *
 * @param fkField   事实表上的外键字段（须已声明 relatedEntity）
 * @param viaEntity 维表实体名（必须与 fk 字段的 relatedEntity 精确一致）
 * @param dimField  维表上的维度字段（归并目标，低基数典型形态）
 */
public record DimensionPath(String fkField, String viaEntity, String dimField) {

    /** 路径分隔符：{@code fk->entity.dim}。 */
    public static final String SEPARATOR = "->";

    /**
     * 解析 group_by 项：含 {@value #SEPARATOR} 的项视为维度路径，否则返回 null
     * （普通分组字段，走原 FT.AGGREGATE 路径）。
     *
     * <p>格式错误（缺实体或缺字段）抛 {@link IrisException}——fail-closed，
     * 报错即文档：模型看到示例即可自我修正，不用猜。
     */
    public static DimensionPath parse(String groupByItem) {
        if (groupByItem == null || !groupByItem.contains(SEPARATOR)) {
            return null;
        }
        String[] head = groupByItem.split(SEPARATOR);
        if (head.length != 2 || head[0].isBlank()) {
            throw new IrisException(ErrorCode.INVALID_QUERY,
                    "维度路径格式非法: " + groupByItem
                            + "（合法写法: fk字段->维表实体.维度字段，箭头前必须是本实体的外键字段）");
        }
        String fk = head[0].trim();
        int dot = head[1].indexOf('.');
        if (dot <= 0 || dot == head[1].length() - 1) {
            throw new IrisException(ErrorCode.INVALID_QUERY,
                    "维度路径格式非法: " + groupByItem
                            + "（箭头后须为 维表实体.维度字段，且该实体字段的 relatedEntity 须指向它）");
        }
        return new DimensionPath(fk, head[1].substring(0, dot).trim(), head[1].substring(dot + 1).trim());
    }

    /** 还原为 group_by 字符串形态（报错/日志用）。 */
    public String path() {
        return fkField + SEPARATOR + viaEntity + "." + dimField;
    }
}

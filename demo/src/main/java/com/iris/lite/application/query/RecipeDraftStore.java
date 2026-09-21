package com.iris.lite.application.query;

import java.util.List;

/**
 * 配方草稿池端口（自进化闭环）：
 * 沉淀「高频顺畅会话的（问题 → 工具调用序列）」与「失败会话的卡点样本」，
 * 供人工审核后整理成 recipe 合入 graph 配方文件热载。
 *
 * <p><b>为什么只存原始素材不自动生成配方</b>：全自动
 * Voyager 模式明确不做——LLM 生成的配方会编造工具名。
 * 闭环的产出物是<b>带上下文的审核素材</b>：顺畅样本给出可直接照抄的工具序列，
 * 失败样本暴露原语缺口。人把前者固化成配方、拿后者决定补什么能力。
 *
 * <p><b>存储语义</b>：每 namespace 一个池子，「最近 N 条」审核队列——
 * 新样本插队头，池子封顶裁尾（防长跑实例无限增长），成功与失败样本同池混排、
 * 用 {@code failed} 标记区分。
 */
public interface RecipeDraftStore {

    /**
     * 追加一条草稿样本。
     *
     * <p>best-effort 语义：实现内部必须吞掉存储异常（只记日志）——
     * 草稿采集是旁路增强，任何故障都不允许影响回答主链路。
     */
    void append(String namespace, RecipeDraft draft);

    /** 读取审核视图：最近的草稿（头部 = 最新）。 */
    List<RecipeDraft> list(String namespace, int limit);

    /** 池内当前样本数（管理面容量指标）。 */
    long size(String namespace);

    /**
     * 一条草稿样本。
     *
     * @param question 用户原始问题
     * @param toolTrace 当前运行的工具调用序列（name + 参数摘要，按发生顺序）
     * @param rounds 实际推理轮数
     * @param toolCalls 工具调用总次数
     * @param failed true = 失败会话（烧满轮数/报错）；false = 顺畅会话
     * @param model 产生该样本的模型（配方质量与模型强相关，审核时要看）
     * @param createdAt 采集时间 epoch 毫秒
     */
    record RecipeDraft(String question, String toolTrace, int rounds, int toolCalls,
                       boolean failed, String model, long createdAt) {
    }
}

package com.iris4j.shared.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）多路召回融合（RAG 检索核心）。
 *
 * <p><b>为什么用 RRF 而非分数加权</b>：多路召回的分数标准天然不可比——
 * 稠密通道是余弦（0-1）、词法通道是 BM25（无界）、改写通道是另一套余弦，
 * 任何线性加权都要为「谁乘几」发明一套拍脑袋系数。RRF 只用<b>排名</b>：
 * {@code score(d) = Σ 1/(k + rank_i(d))}，对分数分布完全免疫；
 * k=60 是原论文（Cormack et al. 2009）与业界缺省，作用是压平头部排名差异——
 * 让「多通道同时命中」的文档稳定胜过「单通道第一」，而不是让单通道第 1 名
 * 靠 rank=1 的 1/61 压倒多通道 rank=5 的 Σ。
 *
 * <p><b>纯静态、无状态、确定性</b>：同输入恒同输出（同分时按 id 字典序
 * 决胜），便于复现问题——与本项目的兜底组件同一品行。
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    /** 融合结果：候选 id + RRF 分数（仅供观测/日志，业务排序即列表顺序）。 */
    public record Fused(String id, double score) {
    }

    /**
     * 融合多路召回的排名列表。
     *
     * @param rrfK     RRF 常数（业界缺省 60）
     * @param topM     融合后候选池上限（进入重排的量）
     * @param channels 每通道按相关度降序的候选 id 列表；null 元素/列表允许（视为空通道）
     */
    public static List<Fused> fuse(int rrfK, int topM, List<List<String>> channels) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> channel : channels) {
            if (channel == null) {
                continue;
            }
            for (int i = 0; i < channel.size(); i++) {
                // rank 从 1 计（论文标准）：第 1 名贡献 1/(k+1)
                scores.merge(channel.get(i), 1.0 / (rrfK + i + 1), Double::sum);
            }
        }
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        List<Fused> out = new ArrayList<>();
        for (int i = 0; i < sorted.size() && i < topM; i++) {
            out.add(new Fused(sorted.get(i).getKey(), sorted.get(i).getValue()));
        }
        return out;
    }
}

package com.iris.lite.application.rerank;

import java.util.List;

/**
 * 交叉编码器重排端口：对 (query, candidate) 句对输出相关性分数。
 *
 * <p><b>动机</b>：LLM 语义缓存的语义通道用双塔（bi-encoder）
 * 余弦判定命中，「9月6号有人下单吗」vs「那2025年的9月6号有人下单吗」这类
 * 微改写句对的余弦高达 0.916——dense 向量压缩全文统计语义，
 * 日期/数值/限定词等细粒度槽位被稀释。交叉编码器把两句拼接后联合编码，
 * 逐 token 注意力直接看到「2025」的差异，判别力远高于余弦。
 *
 * <p><b>角色定位</b>：只做重排（precision），不做召回（recall）。
 * 调用方（LlmCacheService）先用 KNN 召回候选（阈值放宽、宁可多召回、不可漏召回），
 * 再用本端口逐对重排，分数过阈值才判定命中。
 *
 * <p>实现为远程 HTTP（自托管 llama-server 承载 Qwen3-Reranker，见 RemoteCrossEncoderReranker）：
 * 零外部服务依赖、毫秒级推理。
 */
public interface CrossEncoderReranker {

    /**
     * 句对相关性分数（sigmoid 映射到 0-1，越高越相关）。
     *
     * @param query 查询句（如用户新 prompt）
     * @param text  候选句（如缓存条目的原 prompt）
     * @throws RuntimeException 推理失败——调用方应按"重排不可用"保守处理（通过即重新引入误命中风险）
     */
    double score(String query, String text);

    /**
     * 批量重排：对同一 query 与多个候选一次给出分数（与输入等长、按位对应）。
     *
     * <p>RAG 融合候选池（≥10 条）逐对调用会产生 N 次 HTTP 往返；远程实现
     * 覆写为「正向前向一次批量 + 反向逐对」共 N+1 次。缺省实现为逐对循环；
     * blank 候选按 0 分处理（与 {@link #score} 契约一致）。
     *
     * @throws RuntimeException 推理失败——调用方按"重排不可用"保守处理
     */
    default double[] scoreBatch(String query, List<String> texts) {
        double[] out = new double[texts.size()];
        for (int i = 0; i < texts.size(); i++) {
            out[i] = score(query, texts.get(i));
        }
        return out;
    }

    /** 模型指纹（模型名 + 量化档），换模型即换指纹。 */
    String fingerprint();
}

package com.iris4j.memory;

/**
 * 长期记忆检索模式。
 *
 * <ul>
 *   <li><b>SEMANTIC</b>：纯向量 KNN 语义检索（改写/同义表达也能命中）；</li>
 *   <li><b>KEYWORD</b>：词法检索（BM25 排序下推，lex TEXT 索引）；
 *       词法通道不可用/单字查询时落回包含匹配兜底；</li>
 *   <li><b>HYBRID</b>（缺省）：RAG 多路召回——稠密 KNN + BM25 词法
 *       （+ LLM 多查询改写按配置触发）→ RRF 融合 → rerank（可用时）。</li>
 * </ul>
 */
public enum SearchMode {
    SEMANTIC,
    KEYWORD,
    HYBRID
}

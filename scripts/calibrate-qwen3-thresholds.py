#!/usr/bin/env python3
"""
Qwen3-Embedding-0.6B / Qwen3-Reranker-0.6B 阈值标定脚本。

背景：RemoteEmbedder/RemoteReranker 由 bge-small-zh-v1.5/bge-reranker-base
切换到 Qwen3 0.6B 后，旧阈值（0.85/0.50/0.90/0.80）是按旧模型分数分布
标定的，不可直接沿用——本脚本用构造的基准句对重测两类分数分布，给出各阈值建议。

基准设计（正对 = 语义等价；负对 = 语义不同但表面相似）：
  A. 语义缓存值对（semantic-cache.threshold）：
     城市/品牌/类目/订单状态等同字段值域内的等价改写 vs 不同值。
  B. LLM 缓存 prompt 对（llm-cache.recall-threshold + rerank.threshold）：
     同义改写（正）vs 微改写改槽位（数字/日期/指标名，负——槽位敏感形态）
     vs 指标/实体不同（强负）。
  C. 记忆去重对（memory.extractor.dedup-threshold）：
     同一事实改写（正）vs 同域不同事实（负）。

用法：python3 calibrate-qwen3-thresholds.py [embed-base-url] [rerank-base-url]
默认 http://127.0.0.1:8081/v1 与 :8082/v1（可用 IRIS_EMBEDDER_BASE_URL /
IRIS_RERANK_BASE_URL 环境变量覆盖，或命令行参数显式传入）。

标定结论（Qwen3-0.6B）：
  - Embedding：semantic-cache 0.88 可分（正 min 0.899 / 负 max 0.866）；记忆去重
    正负有重叠（正 min 0.813 / 负 max 0.826），取 0.85 偏防丢事实。
  - Rerank：Qwen3-Reranker-0.6B（llama.cpp）双峰分布——域外 ~0，同域一律 0.97+，
    指标级差异（销售额/订单量、女性/男性）全判 0.99+，instruction 前缀亦无效；
    判话题相关不判「同一个问题」，不能担任重排门槛（详见
    RemoteCrossEncoderReranker 类注释）。重排保留本地 bge-reranker-base。
"""
import json
import os
import sys
import urllib.request


def _no_v1(url: str) -> str:
    """兼容带或不带 /v1 的 base-url 传入。"""
    return url[:-3] if url.endswith("/v1") else url


_embed_base = sys.argv[1] if len(sys.argv) > 1 else os.environ.get(
    "IRIS_EMBEDDER_BASE_URL", "http://127.0.0.1:8081/v1")
_rerank_base = sys.argv[2] if len(sys.argv) > 2 else os.environ.get(
    "IRIS_RERANK_BASE_URL", "http://127.0.0.1:8082/v1")
EMBED = _no_v1(_embed_base) + "/v1/embeddings"
RERANK = _no_v1(_rerank_base) + "/v1/rerank"


def post(url, payload):
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def embed_batch(texts):
    """批量向量化（llama.cpp 支持 input 数组），返回 L2 归一化后的向量列表。"""
    data = post(EMBED, {"model": "qwen3-embedding-0.6b", "input": texts})
    out = []
    for item in data["data"]:
        v = [float(x) for x in item["embedding"]]
        norm = (sum(x * x for x in v)) ** 0.5
        out.append([x / norm for x in v] if norm > 0 else v)
    return out


def cosine(a, b):
    return sum(x * y for x, y in zip(a, b))


def rerank_many(query, docs):
    data = post(RERANK, {"model": "qwen3-reranker-0.6b", "query": query, "documents": docs})
    scores = {r["index"]: r["relevance_score"] for r in data["results"]}
    return [scores[i] for i in range(len(docs))]


def stats(name, pos, neg):
    pos_lo = min(pos) if pos else float("nan")
    neg_hi = max(neg) if neg else float("nan")
    print(f"\n== {name} ==")
    print(f"  正对 n={len(pos)}  min={pos_lo:.4f}  mean={sum(pos)/len(pos):.4f}  max={max(pos):.4f}")
    print(f"  负对 n={len(neg)}  min={min(neg):.4f}  mean={sum(neg)/len(neg):.4f}  max={neg_hi:.4f}")
    if pos_lo > neg_hi:
        mid = (pos_lo + neg_hi) / 2
        print(f"  ✅ 完全可分：分界带 [{neg_hi:.4f}, {pos_lo:.4f}]，中点建议 {mid:.4f}")
        return mid
    else:
        overlap_pos = [x for x in pos if x <= neg_hi]
        overlap_neg = [x for x in neg if x >= pos_lo]
        print(f"  ⚠️ 存在重叠：正对中 ≤{neg_hi:.4f} 的 {len(overlap_pos)} 个，"
              f"负对中 ≥{pos_lo:.4f} 的 {len(overlap_neg)} 个——阈值取向保守侧并人工复核重叠对")
        return None


# ---------- A. 语义缓存值对 ----------
VALUE_POS = [
    ("上海", "上海市"), ("北京", "北京市"), ("广州", "广州市"),
    ("华为", "华为HUAWEI"), ("华为", "华为技术有限公司"),
    ("手机", "智能手机"), ("手机", "智能手机产品"),
    ("已发货", "已经发货"), ("现货", "有现货"), ("现货", "现货商品"),
    ("南宁市", "广西南宁市"), ("南宁市", "南宁"),
]
# 注意：值级匹配要求「等价」而非「包含/相关」——上海 vs 上海市浦东新区、
# 已发货 vs 运输中已发货 是不同值，必须标负。
VALUE_NEG = [
    ("上海", "北京"), ("上海", "杭州"),
    ("华为", "小米"), ("华为", "苹果"), ("华为", "vivo"),
    ("手机", "笔记本电脑"), ("手机", "平板电脑"),
    ("已发货", "已签收"), ("已发货", "待发货"), ("已发货", "已取消"),
    ("现货", "预售"), ("现货", "缺货"),
    ("南宁市", "柳州市"), ("南宁市", "桂林市"),
    ("上海", "上海市浦东新区"), ("手机", "手机壳"), ("已发货", "发货中"),
]

# ---------- B. LLM 缓存 prompt 对 ----------
PROMPT_POS = [
    ("上个月的总销售额是多少？", "上个月销售额一共是多少？"),
    ("上个月的总销售额是多少？", "求上月销售总额"),
    ("9月6号有多少笔订单？", "9月6号当天订单量是多少？"),
    ("每个品牌的销量排行", "各品牌销量从高到低排一下"),
    ("女性用户的复购率是多少", "女客户的复购率是多少"),
    ("库存不足100的商品有哪些", "库存少于100件的产品列表"),
]
# 微改写（槽位变化）与强负对
PROMPT_NEG = [
    ("9月6号有多少笔订单？", "那2025年的9月6号有多少笔订单？"),
    ("9月6号有多少笔订单？", "9月7号有多少笔订单？"),
    ("9月6号有多少笔订单？", "9月16号有多少笔订单？"),
    ("上个月的总销售额是多少？", "上个月的总订单量是多少？"),
    ("上个月的总销售额是多少？", "上个月的退款金额是多少？"),
    ("女性用户的复购率是多少", "男性用户的复购率是多少"),
    ("库存不足100的商品有哪些", "库存不足1000的商品有哪些"),
    ("库存不足100的商品有哪些", "库存超过100的商品有哪些"),
]

# ---------- C. 记忆去重事实对 ----------
MEM_POS = [
    ("用户住在上海市浦东新区", "用户的居住地是上海浦东"),
    ("用户养了一只叫豆豆的柯基犬", "用户家有一只柯基，名字叫豆豆"),
    ("用户每天通勤方式是地铁2号线", "用户上班坐2号线地铁通勤"),
    ("用户对花生过敏", "用户吃花生会过敏"),
    ("用户偏好喝无糖美式咖啡", "用户喜欢美式咖啡，要无糖的"),
]
MEM_NEG = [
    ("用户住在上海市浦东新区", "用户的公司在北京市朝阳区"),
    ("用户养了一只叫豆豆的柯基犬", "用户养了一只叫旺财的橘猫"),
    ("用户每天通勤方式是地铁2号线", "用户每天开车通勤"),
    ("用户对花生过敏", "用户对海鲜过敏"),
    ("用户偏好喝无糖美式咖啡", "用户最喜欢喝茉莉花茶"),
]


def main():
    print(f"embed: {EMBED}\nrerank: {RERANK}")

    # A. 语义缓存值对
    texts = [t for p in VALUE_POS for t in p] + [t for p in VALUE_NEG for t in p]
    vecs = embed_batch(texts)
    n = len(VALUE_POS)
    pos = [cosine(vecs[2 * i], vecs[2 * i + 1]) for i in range(n)]
    off = 2 * n
    m = len(VALUE_NEG)
    neg = [cosine(vecs[off + 2 * j], vecs[off + 2 * j + 1]) for j in range(m)]
    mid_a = stats("A. semantic-cache.threshold（值对余弦）", pos, neg)

    # B. LLM 缓存：双塔余弦（recall 线）+ rerank（命中门槛）
    prompts = [t for p in PROMPT_POS for t in p] + [t for p in PROMPT_NEG for t in p]
    pvecs = embed_batch(prompts)
    np_ = len(PROMPT_POS)
    pos_cos = [cosine(pvecs[2 * i], pvecs[2 * i + 1]) for i in range(np_)]
    off = 2 * np_
    nm = len(PROMPT_NEG)
    neg_cos = [cosine(pvecs[off + 2 * j], pvecs[off + 2 * j + 1]) for j in range(nm)]
    mid_cos = stats("B1. llm-cache 双塔余弦（threshold/recall 基础分布）", pos_cos, neg_cos)

    queries = [p[0] for p in PROMPT_POS] + [p[0] for p in PROMPT_NEG]
    docs = []
    for p in PROMPT_POS:
        docs.append(p[1])
    for p in PROMPT_NEG:
        docs.append(p[1])
    all_scores = []
    for q in queries:
        pass  # 逐 query 批量，避免一次文档过多截断
    scores_pos, scores_neg = [], []
    for i, p in enumerate(PROMPT_POS):
        scores_pos.extend(rerank_many(p[0], [p[1]]))
    for p in PROMPT_NEG:
        scores_neg.extend(rerank_many(p[0], [p[1]]))
    mid_r = stats("B2. llm-cache rerank 分数（Qwen3-Reranker）", scores_pos, scores_neg)

    # C. 记忆去重
    texts = [t for p in MEM_POS for t in p] + [t for p in MEM_NEG for t in p]
    mvecs = embed_batch(texts)
    n = len(MEM_POS)
    pos = [cosine(mvecs[2 * i], mvecs[2 * i + 1]) for i in range(n)]
    off = 2 * n
    m = len(MEM_NEG)
    neg = [cosine(mvecs[off + 2 * j], mvecs[off + 2 * j + 1]) for j in range(m)]
    mid_c = stats("C. memory.extractor.dedup-threshold（事实对余弦）", pos, neg)

    print("\n== 阈值建议汇总 ==")
    print(f"  semantic-cache.threshold     = {mid_a:.2f}" if mid_a else "  semantic-cache.threshold     = 人工定")
    print(f"  llm-cache.recall-threshold   = 略低于 B1 正对 min（宁可多召回、不可漏召回，重排门槛兜精度）")
    if mid_r:
        print(f"  llm-cache.rerank.threshold   = {mid_r:.2f}")
    else:
        print("  llm-cache.rerank.threshold   = 人工定（B2 有重叠，先看重叠对）")
    print(f"  memory.extractor.dedup-threshold = {mid_c:.2f}" if mid_c else "  dedup-threshold = 人工定")


if __name__ == "__main__":
    main()

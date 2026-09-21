#!/usr/bin/env python3
"""Qwen3-Reranker-0.6B 模型卡格式复测：transformers + MPS，与 llama.cpp /v1/rerank 结果对照。

模型卡格式（Qwen/Qwen3-Reranker-0.6B）：
  system: Judge whether the Document meets the requirements based on the Query
          and the Instruct provided. Note that the answer can only be "yes" or "no".
  user:   <Instruct>: {instruction}\n<Query>: {query}\n<Document>: {doc}
  assistant: <think>\n\n</think>\n\n
score = softmax([logit_yes, logit_no]) 取最后位置。
"""
import json
import os
import sys

os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

MODEL = "Qwen/Qwen3-Reranker-0.6B"
INSTRUCTION = "Given a web search query, retrieve relevant passages that answer the query"
# 针对 LLM 缓存场景的中文任务指令（支持任务定制 instruction）
INSTRUCTION_ZH = "判断候选文本与查询是否在问同一个问题：日期、数值、指标名称、比较方向必须完全一致，任何一项不同即为不相关"

tok = AutoTokenizer.from_pretrained(MODEL, padding_side="left")
model = AutoModelForCausalLM.from_pretrained(MODEL, torch_dtype=torch.float16).eval()
if torch.backends.mps.is_available():
    model = model.to("mps")

false_tok = tok.convert_tokens_to_ids("no")
true_tok = tok.convert_tokens_to_ids("yes")

PREFIX = '<|im_start|>system\nJudge whether the Document meets the requirements based on the Query and the Instruct provided. Note that the answer can only be "yes" or "no".<|im_end|>\n<|im_start|>user\n'
SUFFIX = "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"


@torch.no_grad()
def score(query, doc, instruction=INSTRUCTION):
    text = (PREFIX
            + f"<Instruct>: {instruction}\n\n<Query>: {query}\n\n<Document>: {doc}"
            + SUFFIX)
    ids = tok(text, return_tensors="pt", truncation=True, max_length=8192).to(model.device)
    logits = model(**ids).logits[0, -1, :]
    pair = torch.stack([logits[false_tok], logits[true_tok]]).float()
    probs = torch.softmax(pair, dim=0)
    return float(probs[1])  # P(yes)


PAIRS = [
    ("P", "上个月的总销售额是多少？", "上个月销售额一共是多少？"),
    ("P", "9月6号有多少笔订单？", "9月6号当天订单量是多少？"),
    ("P", "每个品牌的销量排行", "各品牌销量从高到低排一下"),
    ("P", "女性用户的复购率是多少", "女客户的复购率是多少"),
    ("P", "库存不足100的商品有哪些", "库存少于100件的产品列表"),
    ("N", "9月6号有多少笔订单？", "那2025年的9月6号有多少笔订单？"),
    ("N", "9月6号有多少笔订单？", "9月7号有多少笔订单？"),
    ("N", "上个月的总销售额是多少？", "上个月的总订单量是多少？"),
    ("N", "上个月的总销售额是多少？", "上个月的退款金额是多少？"),
    ("N", "女性用户的复购率是多少", "男性用户的复购率是多少"),
    ("N", "库存不足100的商品有哪些", "库存不足1000的商品有哪些"),
    ("N", "库存不足100的商品有哪些", "库存超过100的商品有哪些"),
    ("N", "上海有多少人口", "北京是中国的首都"),   # 域外强负
]

print("官方英文 instruction（web search 默认任务）:")
pos_s, neg_s = [], []
for tag, q, d in PAIRS:
    s = score(q, d)
    (pos_s if tag == "P" else neg_s).append(s)
    print(f"  {tag} {s:.6f}  {q[:14]} <-> {d[:16]}")
print(f"pos min={min(pos_s):.4f} mean={sum(pos_s)/len(pos_s):.4f} | neg max={max(neg_s):.4f}")

print("\n中文任务 instruction（同题判定）:")
pos_s2, neg_s2 = [], []
for tag, q, d in PAIRS:
    s = score(q, d, instruction=INSTRUCTION_ZH)
    (pos_s2 if tag == "P" else neg_s2).append(s)
    print(f"  {tag} {s:.6f}  {q[:14]} <-> {d[:16]}")
print(f"pos min={min(pos_s2):.4f} mean={sum(pos_s2)/len(pos_s2):.4f} | neg max={max(neg_s2):.4f}")

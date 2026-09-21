#!/usr/bin/env python3
"""原地重写实体文档触发内联索引 v3（全量导入的等效回填路径）。

原理：JSON.GET 原样读出 → JSON.SET $ 写回 → RediSearch 写路径同步更新
该文档涉及的全部索引字段，绕开 ~40 docs/s 的后台节流回填。
内容不变，幂等可断点续跑。

v3：单遍全库 SCAN + 预编码前缀（v2 每 key×87 次 p.encode() 是热点）；
支持 --limit 提前退出。
用法：reindex_touch.py [--limit N] [--batch 300] [--only-prefix SUBSTR]
"""
import argparse
import subprocess
import time

import redis

NS = "ecomm"


def pending_prefixes() -> list[bytes]:
    """枚举索引，返回「尚未回填完成」的实体前缀（已编码，按长度降序）。"""
    out = subprocess.run(
        ["/usr/local/bin/docker", "exec", "iris-redis", "redis-cli", "--raw", "ft._list"],
        capture_output=True, text=True, check=True).stdout.split()
    prefixes = []
    for name in out:
        if not name.startswith(f"iris:{NS}:index:"):
            continue
        info = subprocess.run(
            ["/usr/local/bin/docker", "exec", "iris-redis", "redis-cli", "--raw",
             "ft.info", name],
            capture_output=True, text=True, check=True).stdout.splitlines()
        pct, prefix = None, None
        for i, line in enumerate(info):
            if line == "percent_indexed" and i + 1 < len(info):
                pct = info[i + 1].strip()
            if line == "prefixes" and i + 1 < len(info):
                prefix = info[i + 1].strip()
        if pct != "1" and prefix:
            prefixes.append(prefix.encode())
    prefixes.sort(key=len, reverse=True)
    return prefixes


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--batch", type=int, default=300)
    ap.add_argument("--only-prefix", default=None)
    args = ap.parse_args()

    prefixes = pending_prefixes()
    n_pref = len(prefixes)
    only = args.only_prefix.encode() if args.only_prefix else None
    print(f"pending indexes: {n_pref}", flush=True)

    r = redis.Redis(host="127.0.0.1", port=6379, decode_responses=False)

    # 单遍全库 SCAN
    t0 = time.time()
    keys: list[bytes] = []
    cur = 0
    scans = 0
    while True:
        cur, ks = r.scan(cursor=cur, count=2000)
        for k in ks:
            for p in prefixes:
                if k.startswith(p):
                    keys.append(k)
                    break
        scans += 1
        if scans % 100 == 0:
            print(f"  scan {scans} calls, matched {len(keys)}", flush=True)
        if cur == 0 or (args.limit and len(keys) >= args.limit):
            break
    print(f"scan: {scans} cursor calls, {len(keys)} keys in {time.time()-t0:.1f}s", flush=True)

    if only:
        keys = [k for k in keys if only in k]
    if args.limit:
        keys = keys[: args.limit]
    print(f"target keys: {len(keys)}", flush=True)

    done = 0
    t0 = time.time()
    report = t0
    for start in range(0, len(keys), args.batch):
        chunk = keys[start:start + args.batch]
        pipe = r.pipeline(transaction=False)
        for k in chunk:
            pipe.execute_command("JSON.GET", k)
        docs = pipe.execute()
        pipe = r.pipeline(transaction=False)
        for k, doc in zip(chunk, docs):
            if doc is None:
                continue
            pipe.execute_command("JSON.SET", k, "$", doc)
        pipe.execute(raise_on_error=False)
        done += len(chunk)
        now = time.time()
        if now - report >= 30:
            rate = done / (now - t0)
            eta = (len(keys) - done) / max(rate, 1) / 60
            print(f"progress {done}/{len(keys)} rate={rate:.0f}/s eta={eta:.0f}min", flush=True)
            report = now
    print(f"DONE {done} keys in {(time.time()-t0)/60:.1f} min", flush=True)


if __name__ == "__main__":
    main()

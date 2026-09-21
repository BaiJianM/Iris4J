#!/usr/bin/env python3
"""iris_demo 演示数据集抽样器 — 从本机 Docker 容器生成可入库的瘦身 SQL。

背景：完整数据集 deploy/mysql/init/test_data/iris_demo.sql 约 1.1 GB（3.6M 行），
超出 GitHub 单文件 100 MB 上限且不应公开传播。本脚本从运行中的 iris-mysql
容器抽样生成一个小体积、引用完整（FK 闭包、零孤儿行）的演示 SQL，
输出到 deploy/mysql/init/sample-data/iris_demo_sample.sql。

抽样策略（锚点闭包，世界一致性）：
  1. 小表（<= SMALL_MAX 行）全量——前提是其 FK 父表不依赖抽样集。
  2. 锚点表（ANCHORS，业务枢纽）：按主键 CRC32 取模确定性抽样。
  3. 与锚点 FK 连通的表：闭包推导——
     向下（子表）：保留 FK 指向已选父行的行；
     向上（父表）：补齐已选行引用的全部父行（含自引用树向上递归）。
     向下结果超 CLOSURE_CAP 时确定性等距截断（子集不破坏零孤儿性），
     抑制重尾数据下闭包的正反馈放大。两方向迭代 ROUNDS 轮。
  4. 与锚点不连通的独立大表：取模抽样。

用法：
  python3 scripts/sample-demo-dataset.py
  SMALL_MAX=2000 ANCHOR_CAP="ord_order:800,usr_member:600" python3 scripts/sample-demo-dataset.py

产物不含 CREATE DATABASE/USE——导入方式：
  mysql -D iris_demo -uroot -p < deploy/mysql/init/sample-data/iris_demo_sample.sql
"""

import math
import os
import shutil
import subprocess
import sys

DOCKER_BIN = os.environ.get("DOCKER_BIN") or shutil.which("docker") or "/usr/local/bin/docker"
CONTAINER = os.environ.get("IRIS_MYSQL_CONTAINER", "iris-mysql")
MYSQL_USER = os.environ.get("IRIS_MYSQL_USER", "root")
MYSQL_PASS = os.environ.get("IRIS_MYSQL_PASS", "iris-root")
SOURCE_DB = os.environ.get("IRIS_SOURCE_DB", "iris_demo")
SMALL_MAX = int(os.environ.get("SMALL_MAX", "1500"))   # <= 此行数的表全量
CLOSURE_CAP = int(os.environ.get("CLOSURE_CAP", "1200"))  # 闭包表保留行数上限
ROUNDS = int(os.environ.get("ROUNDS", "3"))            # 闭包迭代轮数
INDIE_CAP = int(os.environ.get("INDIE_CAP", "500"))    # 不连通大表取模目标行数
# 锚点表: 取模目标行数（业务枢纽，决定数据集形状）
DEFAULT_ANCHORS = "ord_order:800,usr_member:1200,prd_product:600"
OUT_PATH = os.environ.get(
    "IRIS_SAMPLE_OUT",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                 "deploy", "mysql", "init", "sample-data", "iris_demo_sample.sql"),
)


def run_sql(sql: str) -> str:
    """在容器内执行 SQL（同一会话），返回 -N -B 原始输出。"""
    p = subprocess.run(
        [DOCKER_BIN, "exec", "-i", CONTAINER,
         "mysql", f"-u{MYSQL_USER}", f"-p{MYSQL_PASS}", "-N", "-B",
         "--default-character-set=utf8mb4", SOURCE_DB],
        input=sql, capture_output=True, text=True)
    if p.returncode != 0:
        sys.exit(f"SQL 执行失败: {p.stderr[:2000]}")
    return p.stdout


def sql_ids(out: str) -> list:
    """GROUP_CONCAT 输出 -> id 列表；空集/NULL -> 空列表。"""
    out = (out or "").strip()
    if not out or out == "NULL":
        return []
    return [v for v in out.split(",") if v]


def run_mysqldump(args: list, where=None) -> str:
    cmd = [DOCKER_BIN, "exec", CONTAINER, "mysqldump",
           f"-u{MYSQL_USER}", f"-p{MYSQL_PASS}",
           "--default-character-set=utf8mb4", "--single-transaction",
           "--set-gtid-purged=OFF", "--no-tablespaces", "--hex-blob",
           "--skip-dump-date", SOURCE_DB] + args
    if where:
        cmd += ["--where", where]
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        sys.exit(f"mysqldump 失败 ({args} where={where}): {p.stderr[:2000]}")
    return p.stdout


def parse_tab(output: str):
    for line in output.splitlines():
        parts = line.split("\t")
        if parts and parts[0]:
            yield parts


def main():
    # ---------- 1. 元数据 ----------
    tables = {}
    for row in parse_tab(run_sql(
            "SELECT table_name FROM information_schema.tables "
            f"WHERE table_schema='{SOURCE_DB}' AND table_type='BASE TABLE'")):
        tables[row[0]] = {}
    print(f"共 {len(tables)} 张表")

    sql = " UNION ALL ".join(f"SELECT '{t}', COUNT(*) FROM `{t}`" for t in tables)
    for t, cnt in parse_tab(run_sql(sql)):
        tables[t]["count"] = int(cnt)

    pk = {t: [] for t in tables}
    for t, col in parse_tab(run_sql(
            "SELECT table_name, column_name FROM information_schema.statistics "
            f"WHERE table_schema='{SOURCE_DB}' AND index_name='PRIMARY' "
            "ORDER BY table_name, seq_in_index")):
        if t in pk:
            pk[t].append(col)
    for t in tables:
        if not pk[t]:
            pk[t] = [c for c, _ in parse_tab(run_sql(
                "SELECT column_name, ordinal_position FROM information_schema.columns "
                f"WHERE table_schema='{SOURCE_DB}' AND table_name='{t}' "
                "ORDER BY ordinal_position"))]

    # FK 边：parents[t] = {父表: [本表FK列,...]}；self_fk 单独记录
    fk_parents = {t: {} for t in tables}
    self_fk = {}  # table -> fk_col
    for row in parse_tab(run_sql(
            "SELECT table_name, column_name, referenced_table_name, referenced_column_name "
            "FROM information_schema.key_column_usage "
            f"WHERE constraint_schema='{SOURCE_DB}' AND referenced_table_name IS NOT NULL")):
        t, col, rt, rcol = row[0], row[1], row[2], row[3]
        if rt == t:
            self_fk[t] = col
        elif rt in fk_parents:
            fk_parents[t].setdefault(rt, []).append(col)
    for t, cols in fk_parents.items():
        for p in cols:
            if len(pk[p]) != 1:
                sys.exit(f"表 {t}: 父表 {p} 非单列主键，本脚本不支持")

    anchors = {}
    for pair in os.environ.get("ANCHORS", DEFAULT_ANCHORS).split(","):
        name, _, cap = pair.partition(":")
        anchors[name.strip()] = int(cap)
    for a in anchors:
        if a not in tables:
            sys.exit(f"锚点表 {a} 不存在")

    def crc_expr(t):
        cols = pk[t]
        return (f"CRC32(`{cols[0]}`)" if len(cols) == 1
                else f"CRC32(CONCAT_WS('#',{','.join('`' + c + '`' for c in cols)}))")

    # ---------- 2. 分类 ----------
    # full: 小表且父表全为 full/anchor-free... 采用保守规则：小表且其父表
    # 都不是「闭包/取模抽样」表。先按拓扑序判 full，其余进入闭包/独立抽样。
    # 闭包可达性：从锚点出发沿 FK 双向可达的表。
    sampled = set(anchors)
    reach = set()
    frontier = set(anchors)
    while frontier:                                  # 双向 BFS 可达集
        nxt = set()
        for t in frontier:
            for p in fk_parents[t]:
                if p not in reach:
                    reach.add(p)
                    nxt.add(p)
            for c, ps in fk_parents.items():
                if t in ps and c not in reach:
                    reach.add(c)
                    nxt.add(c)
        frontier = nxt
    sampled |= reach

    state, note_map = {}, {}
    for t in tables:
        parents_sampled = [p for p in fk_parents[t] if p in sampled]
        if t in anchors:
            state[t] = "sampled"
            note_map[t] = f"anchor(cap={anchors[t]})"
        elif t in reach:
            state[t] = "sampled"
            note_map[t] = "closure"
        elif tables[t]["count"] <= SMALL_MAX and not parents_sampled:
            state[t] = "full"
            note_map[t] = "full"
        else:
            state[t] = "sampled"
            note_map[t] = "indie-mod"

    # ---------- 3. 闭包计算（id 集合，SQL 求值） ----------
    run = "SET SESSION group_concat_max_len = 4194304; "

    def tree_ancestors(t, seed_ids):
        """自引用表：从种子出发沿自引用 FK 向上补齐全部祖先。"""
        if not seed_ids:
            return []
        out = run_sql(run +
            f"WITH RECURSIVE up AS (SELECT `{pk[t][0]}` AS k FROM `{t}` "
            f"WHERE `{pk[t][0]}` IN ({','.join(seed_ids)}) "
            f"UNION SELECT p.`{pk[t][0]}` FROM `{t}` p JOIN up u "
            f"ON p.`{pk[t][0]}` = (SELECT c.`{self_fk[t]}` FROM `{t}` c "
            f"WHERE c.`{pk[t][0]}` = u.k)) "
            f"SELECT GROUP_CONCAT(DISTINCT k) FROM up")
        return sql_ids(out)

    def modulo_ids(t, cap):
        n = max(1, math.ceil(tables[t]["count"] / cap))
        out = run_sql(run + f"SELECT GROUP_CONCAT(`{pk[t][0]}`) FROM ("
                      f"SELECT `{pk[t][0]}` FROM `{t}` "
                      f"WHERE MOD({crc_expr(t)}, {n}) = 0) x")
        return sql_ids(out), n

    def closure_ids(t, parent_terms):
        """按父过滤取本表 id：parent_terms = [(fk_col, parent_ids), ...] AND 连接。
        非空父集条件须含 NULL 引用行（NULL fk = 无父依赖，合法保留）。
        无任何 sampled 父表（纯靠向上闭包填充）时返回空集。"""
        conds = []
        for fk_col, pids in parent_terms:
            conds.append(f"(`{fk_col}` IS NULL OR `{fk_col}` IN ({','.join(pids)}))"
                         if pids else f"`{fk_col}` IS NULL")
        if not conds:
            return []
        out = run_sql(run + f"SELECT GROUP_CONCAT(`{pk[t][0]}`) FROM ("
                      f"SELECT `{pk[t][0]}` FROM `{t}` WHERE "
                      + " AND ".join(f"({c})" for c in conds) + ") x")
        return sql_ids(out)

    def parent_refs(t, my_ids):
        """t 已选行引用的各父表 id（向上闭包一步）。"""
        result = {}
        my_in = ",".join(my_ids) if my_ids else "NULL"
        for p, fk_cols in fk_parents[t].items():
            if state[p] == "full":
                continue
            for fk_col in fk_cols:
                out = run_sql(run + f"SELECT GROUP_CONCAT(DISTINCT `{fk_col}`) FROM `{t}` "
                              f"WHERE `{pk[t][0]}` IN ({my_in}) AND `{fk_col}` IS NOT NULL")
                extra = sql_ids(out)
                result.setdefault(p, []).extend(extra)
        return result

    # 3.1 锚点与独立大表：取模种子
    modulo_n = {}
    ids = {}         # table -> 种子主键集合（锚点/独立表取模结果）
    for t in list(anchors) + [x for x in tables if note_map.get(x) == "indie-mod"]:
        cap = anchors.get(t, INDIE_CAP)
        if self_fk.get(t):
            base, n = modulo_ids(t, cap)
            ids[t] = tree_ancestors(t, base)     # 取模选根后向上收整条祖先链
            note_map[t] += f"+tree(n={n})"
        else:
            ids[t], n = modulo_ids(t, cap)
            modulo_n[t] = n
            note_map[t] += f"(n={n})"

    def cap_ids(lst):
        """确定性等距截断：闭包表行数超限时取子集（子集不破坏零孤儿性）。"""
        if len(lst) <= CLOSURE_CAP:
            return lst
        try:
            lst = sorted(lst, key=lambda v: (0, int(v)) if v.lstrip("-").isdigit()
                         else (1, v))
        except ValueError:
            lst = sorted(lst)
        stride = -(-len(lst) // CLOSURE_CAP)
        return lst[::stride]

    # 3.2 闭包表：向下取子集 + 向上补祖先，迭代至稳定。
    # down[t]=按父条件选中的行；up[t]=被子表引用而强制保留的行；
    # 有效集合 = down ∪ up（up 是闭包必需，不可被 down 重算冲掉）。
    # cap 只作用于 down；空父集跳过其条件（由向上闭包补齐）。
    closure_tabs = [t for t in tables if note_map.get(t, "").startswith("closure")]
    down, up = {}, {}

    def eff(t):
        """有效集合 = 种子（锚点/独立取模）∪ down ∪ up。"""
        return sorted(set(ids.get(t, [])) | set(down.get(t, [])) | set(up.get(t, [])))

    for rnd in range(ROUNDS):
        for t in closure_tabs:                       # 字典序近似拓扑；多轮迭代兜底
            pterms = []
            for p, fk_cols in fk_parents[t].items():
                if state[p] == "full" or not eff(p):
                    continue                         # full 无需条件；空父集本轮跳过
                for fk_col in fk_cols:
                    pterms.append((fk_col, eff(p)))
            down[t] = cap_ids(closure_ids(t, pterms)) if pterms else []
        # 向上：逆拓扑补父行（对有效集合非空的表）
        for t in list(reversed(closure_tabs)) + list(anchors) + \
                 [x for x in tables if note_map.get(x, "").startswith("indie-mod")]:
            cur = eff(t)
            if not cur:
                continue
            for p, extra in parent_refs(t, cur).items():
                if state[p] == "full":
                    continue
                if set(extra) - set(up.get(p, [])):
                    up[p] = sorted(set(up.get(p, [])) | set(extra))

    for t in tables:
        if t in down or t in up:
            ids[t] = eff(t)

    # 3.2b 闭包表里的自引用表：向上补祖先链（如 base_region 被 shop 引用时）
    for t in closure_tabs:
        if self_fk.get(t) and ids.get(t):
            merged = sorted(set(ids[t]) | set(tree_ancestors(t, ids[t])))
            if merged != ids[t]:
                ids[t] = merged

    # 3.2c 迭代向上闭包至不动点：所有保留行引用的祖先链全补齐（FK 深度 ≤ ~6）
    def up_pass():
        grew = False
        for t in list(reversed(closure_tabs)) + list(anchors) + \
                 [x for x in tables if note_map.get(x, "").startswith("indie-mod")]:
            if not ids.get(t):
                continue
            for p, extra in parent_refs(t, ids[t]).items():
                if state[p] == "full":
                    continue
                if set(extra) - set(ids.get(p, [])):
                    ids[p] = sorted(set(ids.get(p, [])) | set(extra))
                    grew = True
        return grew

    for _ in range(10):
        grew = up_pass()
        for t in closure_tabs:
            if self_fk.get(t) and ids.get(t):
                merged = sorted(set(ids[t]) | set(tree_ancestors(t, ids[t])))
                if merged != ids[t]:
                    ids[t] = merged
                    grew = True
        if not grew:
            break

    # 3.2c 严格化：清除父引用不在保留集内的行，迭代至收敛（零孤儿兜底）。
    # 说明：少数挂多重父条件的表（如 mkt_sign_in_record 同时依赖
    # coupon+member）在锚点邻域内交集为空，最终为空表——schema 保留、无数据，
    # 这是零孤儿约束下的正常结果，不影响核心演示链路。
    for _ in range(4):
        changed = False
        for t in closure_tabs:
            cur = ids.get(t)
            if not cur:
                continue
            conds = [f"`{pk[t][0]}` IN ({','.join(cur)})"]
            for p, fk_cols in fk_parents[t].items():
                if state[p] == "full":
                    continue
                pids = ids.get(p, [])
                for fk_col in fk_cols:
                    conds.append(f"(`{fk_col}` IS NULL OR `{fk_col}` IN ({','.join(pids)}))"
                                 if pids else f"`{fk_col}` IS NULL")
            out = run_sql(run + f"SELECT GROUP_CONCAT(`{pk[t][0]}`) FROM `{t}` WHERE "
                          + " AND ".join(f"({c})" for c in conds))
            new = sql_ids(out)
            if set(new) != set(cur):
                ids[t] = new
                changed = True
        if not changed:
            break

    # 3.3 闭包/独立表最终落地为 where；超大集合走 helper 库子查询（防 argv 爆炸）
    HELPER_DB = "_iris_sample_helper"
    run_sql(f"DROP DATABASE IF EXISTS {HELPER_DB}; CREATE DATABASE {HELPER_DB}")
    helper_used = []
    big = sorted(ids.items(), key=lambda kv: -len(kv[1]))[:12]
    print("闭包集合 top12: " + ", ".join(f"{t}={len(v)}" for t, v in big))
    where_map = {}
    for t in tables:
        if state[t] == "full":
            where_map[t] = None
        elif t in ids:
            if not ids[t]:
                where_map[t] = "FALSE"               # 闭包结果为空（孤立片段）
                note_map[t] += " [EMPTY!]"
            elif len(ids[t]) > 8000:
                numeric = all(v.lstrip("-").isdigit() for v in ids[t])
                lit = (lambda v: v) if numeric else (lambda v: "'" + v.replace("'", "''") + "'")
                col_type = "BIGINT" if numeric else "VARCHAR(64)"
                run_sql(run + f"CREATE TABLE {HELPER_DB}.`ids_{t}` "
                        f"(id {col_type} PRIMARY KEY) AS SELECT * FROM (VALUES "
                        + ",".join(f"ROW({lit(v)})" for v in ids[t]) + ") AS v(id)")
                where_map[t] = f"`{pk[t][0]}` IN (SELECT id FROM {HELPER_DB}.`ids_{t}`)"
                helper_used.append(t)
            else:
                where_map[t] = f"`{pk[t][0]}` IN ({','.join(ids[t])})"
        else:
            where_map[t] = "FALSE"
            note_map[t] += " [NOIDS!]"

    # ---------- 4. 生成 SQL ----------
    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    header = (
        "-- iris_demo sampled dataset — generated by scripts/sample-demo-dataset.py\n"
        f"-- Source: {SOURCE_DB} -> anchor-closure sampling (SMALL_MAX={SMALL_MAX},"
        f" anchors={DEFAULT_ANCHORS})\n"
        "-- FK-closed (no orphan rows); deterministic (CRC32 modulo seeds).\n"
        "-- Import:  mysql -D iris_demo -uroot -p < iris_demo_sample.sql\n\n")
    parts = [header, run_mysqldump(["--no-data"])]
    full_tables = [t for t in sorted(tables) if state[t] == "full"]
    if full_tables:
        parts.append(run_mysqldump(["--no-create-info"] + full_tables))
    for t in sorted(tables):
        if state[t] != "full":
            parts.append(run_mysqldump(["--no-create-info", t], where=where_map[t]))

    with open(OUT_PATH, "w", encoding="utf-8") as f:
        f.write("\n".join(parts))
    if helper_used:
        print(f"helper 子查询表: {', '.join(helper_used)}")
    run_sql(f"DROP DATABASE IF EXISTS {HELPER_DB}")
    size_mb = os.path.getsize(OUT_PATH) / 1024 / 1024

    # ---------- 5. 报告 ----------
    print(f"\n输出: {OUT_PATH}  ({size_mb:.1f} MB)")
    print(f"{'表':40s} {'全量行数':>9s}  {'方式':22s} 保留")
    kept_total = full_cnt = 0
    for t in sorted(tables):
        cnt = tables[t]["count"]
        kept = len(ids[t]) if t in ids else (cnt if state[t] == "full" else 0)
        if state[t] == "full":
            full_cnt += 1
        kept_total += kept
        print(f"{t:40s} {cnt:>9d}  {note_map.get(t,'?'):22s} {kept}")
    print(f"\nfull 表 {full_cnt} 张；合计保留约 {kept_total} 行")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""从 information_schema 自动生成 ecomm namespace 的 schema yml 与 cdc sources。

- 全量重生成（幂等）：数据库 information_schema 是唯一真相源，重跑即覆盖
- 字段来源：主键 + 二级索引列 + created_at（索引列按类型映射 numeric/tag）
- relatedEntity：优先用真实外键（key_column_usage），无约束的 _id 列用最短表名匹配兜底
- 产物：deploy/schema/ecomm.<table>.yml + /tmp/cdc_sources.yml 片段

用法：python3 deploy/schema/gen_all_yml.py
"""
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

CONTAINER = "iris-mysql"
PWD = "iris-root"
DB = "iris_demo"
NS = "ecomm"
HERE = Path(__file__).resolve().parent
HANDWRITTEN = {
    "usr_member", "mch_shop", "prd_category", "prd_product",
    "prd_sku", "ord_order", "ord_order_item",
}
MAX_INDEXED = 15


def mysql(sql: str) -> list[tuple]:
    out = subprocess.run(
        ["/usr/local/bin/docker", "exec", "-i", CONTAINER, "mysql",
         "-uroot", f"-p{PWD}", "-N", "-B", "-e", sql],
        capture_output=True, text=True, check=True).stdout
    return [tuple(line.split("\t")) for line in out.splitlines() if line]


def sql_type(data_type: str) -> str:
    d = data_type.lower()
    if d in ("bigint",):
        return "LONG"
    if d in ("int", "integer", "mediumint", "smallint", "tinyint", "year"):
        return "INT"
    if d in ("decimal", "numeric", "double", "float"):
        return "DOUBLE"
    if d in ("date", "datetime", "timestamp", "time"):
        return "LONG"   # Debezium: date=天数 / datetime=毫秒
    return "STRING"


def is_numeric_type(t: str) -> bool:
    return t in ("LONG", "INT", "DOUBLE")


def related_for(table: str, col: str, tables: set[str],
                fk_map: dict[tuple[str, str], str]) -> str | None:
    """FK 列 -> 关联实体名；真实外键优先，否则最短表名兜底。"""
    if (table, col) in fk_map:
        return fk_map[(table, col)]
    m = re.match(r"^(.+)_id$", col)
    if not m:
        return None
    base = m.group(1)
    if base in tables:
        return base
    cands = sorted((t for t in tables if t.endswith("_" + base)), key=len)
    return cands[0] if cands else None


def main() -> None:
    tables = {r[0] for r in mysql(
        "SELECT table_name FROM information_schema.tables "
        f"WHERE table_schema='{DB}' AND table_type='BASE TABLE'")}
    print(f"表总数: {len(tables)}")

    cols = defaultdict(list)          # table -> [(col, data_type, col_key)]
    for t, c, dt, ck in mysql(
            "SELECT table_name, column_name, data_type, column_key "
            "FROM information_schema.columns "
            f"WHERE table_schema='{DB}' ORDER BY table_name, ordinal_position"):
        cols[t].append((c, dt.lower(), ck))

    idx_cols = defaultdict(set)       # table -> {indexed col}
    for t, _idx, c in mysql(
            "SELECT table_name, index_name, column_name FROM information_schema.statistics "
            f"WHERE table_schema='{DB}' AND index_name <> 'PRIMARY'"):
        idx_cols[t].add(c)

    fk_map = {}                       # (table, col) -> referenced table
    for t, c, rt in mysql(
            "SELECT table_name, column_name, referenced_table_name "
            "FROM information_schema.key_column_usage "
            f"WHERE table_schema='{DB}' AND referenced_table_name IS NOT NULL"):
        fk_map[(t, c)] = rt
    sources = []
    generated = 0
    for t in sorted(tables):
        pk = [c for c, _dt, ck in cols[t] if ck == "PRI"] or ["id"]
        want: list[tuple[str, str]] = []   # (col, sql_type)
        seen = set(pk)
        for c in pk:
            sqlt = next(dt for cc, dt, _ in cols[t] if cc == c)
            want.append((c, sqlt))
        for c, dt, _ck in cols[t]:
            if c in seen:
                continue
            if c in idx_cols[t] or c == "created_at":
                want.append((c, dt))
                seen.add(c)
        # 列数截断（索引字段上限）
        want = want[:1 + MAX_INDEXED]

        lines = [f"namespace: {NS}", f"entity: {t}",
                 "primaryKeys:", f"  - {pk[0]}", "fields:"]
        for c, dt in want:
            jt = sql_type(dt)
            lines += [f"  - name: {c}", f"    type: {jt}"]
            if c in pk:
                lines.append("    indexed: true")
            else:
                lines.append("    index: numeric" if is_numeric_type(jt) else "    index: tag")
            if c != pk[0]:
                rel = related_for(t, c, tables, fk_map)
                if rel and rel in tables and rel != t or (rel == t and rel in tables):
                    # 自引用（parent_id）也声明，支持树导航
                    lines.append(f"    relatedEntity: {rel}")
                    lines.append(f"    description: 外键关联 {rel}")
                elif c.endswith("_at") and jt == "LONG":
                    lines.append("    description: 毫秒时间戳" if dt != "date" else "    description: 日期（距纪元天数）")
        (HERE / f"ecomm.{t}.yml").write_text("\n".join(lines) + "\n", encoding="utf-8")
        generated += 1
        sources.append(
            f"      - namespace: {NS}\n"
            f"        stream: iris.{DB}.{t}\n"
            f"        entity: {t}\n"
            f"        group: redis-iris-java-ecomm-{t}\n"
            f"        consumer: cdc-ecomm-{t}\n"
            f"        block-ms: 1000")

    Path("/tmp/cdc_sources.yml").write_text("\n".join(sources) + "\n", encoding="utf-8")
    print(f"生成 yml: {generated} 张（全量覆盖）")
    print(f"cdc sources 片段: /tmp/cdc_sources.yml（{len(sources)} 条）")
    # 自检：yml 总数应等于表总数
    files = list(HERE.glob("ecomm.*.yml"))
    assert len(files) == len(tables), f"yml 数 {len(files)} != 表数 {len(tables)}"
    print(f"自检通过: deploy/schema 共 {len(files)} 个实体 yml")


if __name__ == "__main__":
    sys.exit(main())

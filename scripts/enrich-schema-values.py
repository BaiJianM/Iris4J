#!/usr/bin/env python3
"""schema yml 字段 values（值域语义）补全。

通用管线：从源库 information_schema 读枚举/状态类列（tinyint/smallint）的
column_comment，解析「前缀：0待审 1通过 2驳回」形态的值域声明，
写入 deploy/schema 下对应 yml 字段的 values 注记——get_schema/MCP 语义化
工具即向 LLM 输出该值域（聚合口径类问题的正答前提）。

只补缺失的 values，已有绝不动；values 不参与索引定义比对，不触发索引重建。
注释无值域（如「包裹数量」）自动跳过；形态可疑（前导数字解析歧义）保守放弃
并上报，绝不猜测语义。

用法：enrich-schema-values.py [--apply]（默认 dry-run）
"""
import glob
import re
import subprocess
import sys

import yaml

SCHEMA_GLOB = "<REPO_ROOT>/deploy/schema/ecomm.*.yml"
DOCKER_MYSQL = ["/usr/local/bin/docker", "exec", "iris-mysql", "mysql", "-uroot",
                "-piris-root", "-D", "information_schema",
                "--default-character-set=utf8mb4", "-N", "-e"]

APPLY = "--apply" in sys.argv


def fetch_comments() -> dict[tuple[str, str], str]:
    """(table, column) -> comment，仅 tinyint/smallint/enum 列。"""
    sql = ("SELECT table_name, column_name, column_comment FROM columns "
           "WHERE table_schema='iris_demo' "
           "AND data_type IN ('tinyint','smallint','enum')")
    out = subprocess.run(DOCKER_MYSQL + [sql], capture_output=True, text=True,
                         check=True).stdout
    result = {}
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) == 3:
            result[(parts[0], parts[1])] = parts[2]
    return result


def parse_values(comment: str) -> str | None:
    """从注释解析值域，返回 "0=未付; 1=已付" 形态；无法确定返回 None。

    规则：含「：/:」取冒号后主体；主体按空白分词，收集前导数字 token；
    ≥2 个且数字互不重复、剩余部分非空且不以 '-' 开头才认定值域。
    """
    if "：" in comment:
        body = comment.split("：", 1)[1]
    elif ":" in comment:
        body = comment.split(":", 1)[1]
    else:
        return None
    tokens = body.split()
    entries = []
    seen_nums = set()
    for tok in tokens:
        m = re.match(r"^(\d+)(\D.*)$", tok)
        if not m:
            continue
        num, label = m.group(1), m.group(2).strip()
        if not label or label.startswith("-") or num in seen_nums:
            return None  # 形态歧义，保守放弃
        seen_nums.add(num)
        entries.append(f"{num}={label}")
    if len(entries) < 2:
        return None
    return "; ".join(entries)


def load_yml(path: str) -> dict:
    with open(path, encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def write_yml(path: str, doc: dict) -> None:
    """手写 emitter，与 enrich-schema-descriptions.py 保持同一布局风格。"""
    lines = [f"namespace: {doc['namespace']}", f"entity: {doc['entity']}"]
    if doc.get("description"):
        lines.append(f"description: {doc['description']}")
    lines.append("primaryKeys:")
    for pk in doc["primaryKeys"]:
        lines.append(f"  - {pk}")
    lines.append("fields:")
    for f in doc["fields"]:
        lines.append(f"  - name: {f['name']}")
        lines.append(f"    type: {f['type']}")
        if f.get("indexed"):
            lines.append("    indexed: true")
        if f.get("index"):
            lines.append(f"    index: {f['index']}")
        if f.get("relatedEntity"):
            lines.append(f"    relatedEntity: {f['relatedEntity']}")
        if f.get("tags"):
            lines.append("    tags:")
            for t in f["tags"]:
                lines.append(f"      - {t}")
        if f.get("description"):
            lines.append(f"    description: {f['description']}")
        if f.get("values"):
            lines.append(f"    values: {f['values']}")
    with open(path, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


def main() -> None:
    comments = fetch_comments()
    print(f"源库枚举类列：{len(comments)}")

    parsed = {}
    skipped = []
    for (tbl, col), cmt in comments.items():
        v = parse_values(cmt)
        if v:
            parsed[(tbl, col)] = v
        elif cmt:
            skipped.append(f"{tbl}.{col}: {cmt}")
    print(f"解析出值域：{len(parsed)}；注释无值域/形态歧义：{len(skipped)}")

    updated = 0
    already = 0
    unmatched = []
    for path in sorted(glob.glob(SCHEMA_GLOB)):
        doc = load_yml(path)
        entity = doc["entity"]
        dirty = False
        for f in doc.get("fields", []):
            key = (entity, f["name"])
            if key not in parsed:
                continue
            if f.get("values"):
                already += 1
                continue
            f["values"] = parsed[key]
            dirty = True
            updated += 1
        if dirty and APPLY:
            write_yml(path, doc)
    print(f"yml 侧：补写 {updated}，已有 {already}")
    missing = [f"{t}.{c}" for (t, c) in parsed
               if not glob.glob(f"{SCHEMA_GLOB.replace('ecomm.*.yml', 'ecomm.' + t + '.yml')}")]
    if missing:
        print(f"无对应 yml 的值域列（表未建 schema）：{len(missing)}")
    if not APPLY:
        print("dry-run 完成，未落盘。加 --apply 生效。")
        for s in skipped[:10]:
            print(f"  跳过示例: {s}")


if __name__ == "__main__":
    main()

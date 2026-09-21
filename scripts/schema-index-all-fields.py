#!/usr/bin/env python3
"""一次性脚本：deploy/schema 全部 yml 补全字段索引声明。

规则：
- 以 MySQL information_schema 为准补齐缺失字段（blob 类超大字段除外：text/json）
- 类型映射：bigint→LONG；int/smallint/tinyint/mediumint/year→INT；
  decimal/double/float→DOUBLE；datetime/timestamp→LONG；date→INT；其余字符串→STRING
- 索引形态：数值→index: numeric；STRING 按语义：名称/描述类→text，其余→tag
- 已有字段声明（type/index/relatedEntity/description）一律不动；
  仅对既无 index 又无 indexed 的存量字段补 index
- 主键字段缺失时按既有惯例补 indexed: true

用法：schema-index-all-fields.py [--dry-run]
"""
import re
import sys
from pathlib import Path

import yaml

SCHEMA_DIR = Path(__file__).resolve().parents[1] / "deploy" / "schema"
COLUMNS_TSV = Path("/tmp/iris_demo_columns.tsv")

SKIP_DATA_TYPES = {"text", "tinytext", "mediumtext", "longtext",
                   "blob", "tinyblob", "mediumblob", "longblob", "json"}

TYPE_MAP = {
    "bigint": "LONG",
    "int": "INT", "smallint": "INT", "mediumint": "INT",
    "tinyint": "INT", "year": "INT",
    "decimal": "DOUBLE", "double": "DOUBLE", "float": "DOUBLE",
    "datetime": "LONG", "timestamp": "LONG",
    "date": "INT",
}
TEXT_NAME_RE = re.compile(
    r"(name|title|desc|remark|content|label|summary|text|address|comment)", re.I)


def load_columns():
    tables = {}
    for line in COLUMNS_TSV.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) != 4:
            continue
        table, column, data_type, _pos = parts
        tables.setdefault(table, []).append((column, data_type.lower()))
    return tables


def index_form_for(name: str, yml_type: str) -> str:
    if yml_type in ("LONG", "INT", "DOUBLE"):
        return "numeric"
    return "text" if TEXT_NAME_RE.search(name) else "tag"


def emit(doc: dict) -> str:
    lines = []
    lines.append(f"namespace: {doc['namespace']}")
    lines.append(f"entity: {doc['entity']}")
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
    return "\n".join(lines) + "\n"


def main() -> None:
    dry = "--dry-run" in sys.argv
    columns = load_columns()
    stats = {"files": 0, "fields_added": 0, "index_filled": 0, "skipped_blob": 0}
    skipped_cols = []

    for path in sorted(SCHEMA_DIR.glob("*.yml")):
        if path.name == "README.md":
            continue
        doc = yaml.safe_load(path.read_text(encoding="utf-8"))
        if not doc or "entity" not in doc:
            continue
        entity = doc["entity"]
        table = entity.split(".", 1)[1] if "." in entity else entity
        ns = doc["namespace"]
        mysql_table = table if table.startswith(ns + "_") else table
        cols = columns.get(mysql_table)
        if cols is None:
            continue

        existing = {f["name"]: f for f in doc["fields"]}
        pk = doc["primaryKeys"][0]

        for f in doc["fields"]:
            if not f.get("index") and not f.get("indexed"):
                f["index"] = index_form_for(f["name"], f["type"])
                stats["index_filled"] += 1

        added = 0
        for col, data_type in cols:
            if col in existing:
                continue
            if data_type in SKIP_DATA_TYPES:
                skipped_cols.append(f"{mysql_table}.{col}({data_type})")
                stats["skipped_blob"] += 1
                continue
            yml_type = TYPE_MAP.get(data_type)
            if yml_type is None:
                yml_type = "STRING"
            new_field = {"name": col, "type": yml_type}
            if col == pk:
                new_field["indexed"] = True
            else:
                new_field["index"] = index_form_for(col, yml_type)
            doc["fields"].append(new_field)
            existing[col] = new_field
            added += 1
        stats["fields_added"] += added
        stats["files"] += 1
        if not dry:
            path.write_text(emit(doc), encoding="utf-8")
        print(f"{path.name}: +{added} fields")

    print(f"\n== summary: {stats}")
    if skipped_cols:
        print("blob 类跳过:")
        for s in skipped_cols:
            print(f"  - {s}")
    if dry:
        print("（dry-run，未写盘）")


if __name__ == "__main__":
    main()

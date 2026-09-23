#!/usr/bin/env bash
# 查询路径规划：关系图谱引导工具
#
# 【为什么是离线脚本而不是应用内端点】iris4j 运行时不连源库（CDC 单向投影、
# agent 不回查主库是硬边界）——表结构解析只能发生在接入期，由本工具在源库侧
# 只读执行，产出图谱草稿供人工审核后合入 deploy/graph/。AI 不直接改任何东西。
#
# 子命令：
#   analyze  解析 information_schema（表/列/PK/显式 FK）+ 命名约定推断 FK 候选
#            （置信度 explicit / inferred-high / inferred-low），输出 {ns}.graph.draft.yml
#   drift    对账：源库表集合 vs deploy/schema/{ns}.*.yml 实体集合 vs 现有 graph yml，
#            报告新增表/下线表/CDC 排除表/关系边差异（漂移对账）
#
# 前置：iris-mysql 容器可用；deploy/schema/{ns}.*.yml 已建模。
# 只读：全部查询走 information_schema，不写源库任何数据。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
MC="${IRIS_MYSQL_CONTAINER:-iris-mysql}"
DB="${IRIS_DB:-iris_demo}"
NS="${IRIS_NS:-ecomm}"
SCHEMA_DIR="${IRIS_SCHEMA_DIR:-$ROOT/deploy/schema}"
GRAPH_DIR="${IRIS_GRAPH_DIR:-$ROOT/deploy/graph}"
CMD="${1:-analyze}"

MYSQL="docker exec $MC mysql -uroot -piris-root -N --default-character-set=utf8mb4"

q() { $MYSQL -e "$1" 2>/dev/null; }

case "$CMD" in
analyze)
  echo "== 1. 拉取 information_schema（只读）==" >&2
  q "SELECT table_name, table_comment FROM information_schema.tables WHERE table_schema='${DB}' AND table_type='BASE TABLE' ORDER BY table_name;" > /tmp/g1-tables.tsv
  q "SELECT table_name, column_name, data_type, column_key FROM information_schema.columns WHERE table_schema='${DB}' ORDER BY table_name, ordinal_position;" > /tmp/g1-columns.tsv
  q "SELECT table_name, column_name, referenced_table_name, referenced_column_name FROM information_schema.key_column_usage WHERE table_schema='${DB}' AND referenced_table_name IS NOT NULL ORDER BY table_name;" > /tmp/g1-fks.tsv
  echo "  表=$(grep -c . /tmp/g1-tables.tsv) 列=$(grep -c . /tmp/g1-columns.tsv) 显式FK=$(grep -c . /tmp/g1-fks.tsv)" >&2

  echo "== 2. 解析+推断 ==" >&2
  /usr/bin/python3 - "$NS" "$SCHEMA_DIR" <<'PYEOF'
import sys, os, glob
ns, schema_dir = sys.argv[1], sys.argv[2]

tables = {}   # table -> comment
for line in open('/tmp/g1-tables.tsv'):
    parts = line.rstrip('\n').split('\t')
    if len(parts) >= 1 and parts[0]:
        tables[parts[0]] = parts[1] if len(parts) > 1 else ''

# 已建模实体（schema yml 文件名 = {ns}.{entity}.yml）
entities = set()
for f in glob.glob(os.path.join(schema_dir, f'{ns}.*.yml')):
    entities.add(os.path.basename(f)[len(ns)+1:-4])

edges = {}    # (from_table, field) -> (to_table, confidence, to_column)
for line in open('/tmp/g1-fks.tsv'):
    p = line.rstrip('\n').split('\t')
    if len(p) < 4: continue
    edges[(p[0], p[1])] = (p[2], 'explicit', p[3])

# 推断：xxx_id 非显式 FK → 去后缀 token → 表名含 token 的唯一候选 = inferred-high
for line in open('/tmp/g1-columns.tsv'):
    p = line.rstrip('\n').split('\t')
    if len(p) < 4 or not p[1].endswith('_id'): continue
    tbl, col = p[0], p[1]
    if (tbl, col) in edges: continue
    token = col[:-3]
    cands = [t for t in tables if token in t and t != tbl]
    # 强推断：表名以 token 结尾（usr_member ✕ member_id、afc_after_sale ✕ after_sale_id）；
    # 复合后缀表（usr_member_address 之于 member_id）被排除
    strong = [t for t in cands if t == token or t.endswith('_' + token)]
    pool = strong if strong else cands
    if len(pool) == 1:
        edges[(tbl, col)] = (pool[0], 'inferred-high', 'id')
    elif len(pool) > 1:
        edges[(tbl, col)] = ('|'.join(sorted(pool)), 'inferred-low', 'id')

# 输出草稿
out = [f'# {ns} 关系图谱草稿（graph-bootstrap.sh analyze 生成，需人工审核后改名为 {ns}.graph.yml）',
       f'# 生成依据：information_schema 显式 FK + 命名约定推断；confidence: explicit > inferred-high > inferred-low',
       'namespace: ' + ns, 'recipes: []', 'descriptions:']
descriptions = {t: c for t, c in tables.items() if c and t in entities}
rel_lines = []
for (f_tbl, f_col), (t_tbl, conf, t_col) in sorted(edges.items()):
    if f_tbl not in entities or '|' in t_tbl:
        if f_tbl in entities and '|' in t_tbl:
            rel_lines.append(f'  # [inferred-low] {f_tbl}.{f_col} 候选目标不唯一: {t_tbl}')
        continue
    if t_tbl not in entities: continue
    rel_lines.append(f'  # [{conf}] {f_tbl}.{f_col} -> {t_tbl}.{t_col}')
print('\n'.join(out))
print('relations:')
print('\n'.join(rel_lines) if rel_lines else '  # (none)')
print('# --- 源库表注释（可直接作为 descriptions；仅含已建模实体）---')
for t, c in sorted(descriptions.items()):
    print(f'#   {t}: {c}')
PYEOF
  ;;

drift)
  echo "== 源库 vs schema yml vs graph 对账（namespace=${NS}）==" >&2
  q "SELECT table_name FROM information_schema.tables WHERE table_schema='${DB}' AND table_type='BASE TABLE';" > /tmp/g1-tables.tsv
  /usr/bin/python3 - "$NS" "$SCHEMA_DIR" "$GRAPH_DIR" <<'PYEOF'
import sys, os, glob
ns, sd, gd = sys.argv[1], sys.argv[2], sys.argv[3]
src = {l.strip() for l in open('/tmp/g1-tables.tsv') if l.strip()}
yml = {os.path.basename(f)[len(ns)+1:-4] for f in glob.glob(os.path.join(sd, f'{ns}.*.yml'))}
graph = os.path.join(gd, f'{ns}.graph.yml')
graph_relations = 0
if os.path.exists(graph):
    graph_relations = sum(1 for l in open(graph) if '->' in l and '#' not in l.split('->')[0])
print(f'源库表={len(src)}  schema yml 实体={len(yml)}  graph 关系边(已合入)={graph_relations}')
new = sorted(src - yml)
gone = sorted(yml - src)
if new:
    print(f'\n[源库有表、无 schema yml] {len(new)} 张（未建模或已 CDC 排除）:')
    for t in new: print('  -', t)
if gone:
    print(f'\n[有 schema yml、源库无表] {len(gone)} 张（表被删除?）:')
    for t in gone: print('  -', t)
if not new and not gone:
    print('\n[PASS] 表集合完全一致')
PYEOF
  ;;

enhance)
  # AI 增强：把 analyze 草稿（关系边 + 表注释）交给 LLM，生成 recipes/descriptions 草稿。
  # 产出永远只是「建议」，人工审核合入 {ns}.graph.yml——AI 生成、人工批准、热载生效。
  DRAFT="${2:?用法: graph-bootstrap.sh enhance <draft-file>}"
  [ -f "$DRAFT" ] || { echo "草稿不存在: $DRAFT" >&2; exit 1; }
  APP_YML="$ROOT/api/src/main/resources/application.yml"
  # 注意：application.yml 里有两处 api-key（64 行 agent 安全的 IRIS_API_KEY、126 行 LLM 的
  # IRIS_LLM_API_KEY）——必须匹配后者，否则拿到空 key 打 DashScope 得 401
  KEY="${IRIS_LLM_API_KEY:-$(grep -m1 'IRIS_LLM_API_KEY' "$APP_YML" | sed 's/.*IRIS_LLM_API_KEY:\([^}]*\).*/\1/')}"
  BASE=$(grep -m1 'base-url: https' "$APP_YML" | awk '{print $2}')
  MODEL=$(grep -m1 '^    model: ' "$APP_YML" | awk '{print $2}')
  [ -n "$KEY" ] || { echo "未找到 LLM key（设 IRIS_LLM_API_KEY）" >&2; exit 1; }
  echo "== 调 LLM 生成配方草稿（model=${MODEL} ，key 不回显）==" >&2
  /usr/bin/python3 - "$DRAFT" "$KEY" "$BASE" "$MODEL" <<'PYEOF'
import sys, json, urllib.request
draft_path, key, base, model = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
draft = open(draft_path).read()
# 控制规模：边全给（~10KB）、注释全给；超出 60KB 截断
if len(draft) > 60000:
    draft = draft[:60000] + '\n# ...（截断）'
prompt = f"""你是数据库查询路径规划助手。下面是一个电商库的关系图谱草稿（外键边+置信度+表注释）。
请为 AI 数据助手生成「查询路径配方」——让它用最少查询次数回答典型业务问题。

要求：
1. 挑 5 个最典型的高频意图（会员订单查询、订单明细、售后、商品、统计聚合等）
2. 每条 recipe 字段：intent / match(触发词列表) / anchor(锚点查询) / path(1-3 步，每步写清 entity+filters 字段) / tools / hint
3. 字段名与实体名必须严格使用图谱中出现的真实名称，禁止编造
4. 统计类意图必须引导用 aggregate_entity，禁止逐页拉取计数

只输出 YAML，顶层键为 recipes:（列表），不要 markdown 代码块标记，不要任何解释。

【图谱草稿】
{draft}"""
body = json.dumps({"model": model, "messages": [{"role": "user", "content": prompt}],
                   "max_tokens": 8192, "temperature": 0.3}).encode()
req = urllib.request.Request(base.rstrip('/') + '/chat/completions', data=body,
    headers={"Content-Type": "application/json", "Authorization": "Bearer " + key})
with urllib.request.urlopen(req, timeout=300) as r:
    resp = json.load(r)
content = resp.get("choices", [{}])[0].get("message", {}).get("content", "")
print(content.strip())
PYEOF
  ;;

*)
  echo "用法: graph-bootstrap.sh analyze|drift" >&2; exit 1;;
esac

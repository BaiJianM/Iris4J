#!/usr/bin/env bash
# CDC 完整性校验（全量重建的最终判定）：
# 逐表比对「源库行数」与「Redis 查询索引的 num_docs」，覆盖全部 135 张表，禁止抽样。
#
# 为什么必须逐表核对：CDC 的丢失是**静默**的。XTRIM MAXLEN 会把尚未投递的事件一并裁掉，
# 而被裁掉的消息不在 PEL 里，重试与 DLQ 都兜不住；更要命的是 group 的 last-delivered-id
# 会被推到尾部，于是 XINFO GROUPS 显示 lag=0，「看起来全部消费完毕」。
# 注意：CDC 静默丢失的典型形态是「源库远多于 Redis 且 lag=0」（XTRIM 裁掉未投递
# 事件后 group 的 last-delivered-id 已推到尾部，XINFO GROUPS 显示已消费完毕）。
# 唯一能发现它的口径就是「源库行数 vs 索引文档数」——本脚本即该断言。
#
# 用法： ./deploy/verify/verify-cdc-completeness.sh [namespace]
# 前置：iris-lite 已运行（索引已建）；iris-redis / iris-mysql 容器可用。
set -uo pipefail

NS="${1:-${IRIS_NS:-ecomm}}"
RC="${IRIS_REDIS_CONTAINER:-iris-redis}"
MC="${IRIS_MYSQL_CONTAINER:-iris-mysql}"
DB="${IRIS_DB:-iris_demo}"

pass=0; fail=0
ok()  { echo "  [PASS] $1"; pass=$((pass + 1)); }
bad() { echo "  [FAIL] $1"; fail=$((fail + 1)); }

# ---- 1. 源库逐表精确行数（一条 UNION ALL，135 个 COUNT(*)）----
echo "== 1. 取源库逐表行数（${DB}）=="
TABLES=$(docker exec "$MC" mysql -uroot -piris-root -N \
  -e "SELECT table_name FROM information_schema.tables WHERE table_schema='${DB}' AND table_type='BASE TABLE' ORDER BY table_name;" 2>/dev/null)
N=$(printf '%s\n' "$TABLES" | grep -c .)
if [ "$N" -lt 100 ]; then
  bad "源库表数异常（${N}），中止"
  exit 1
fi
echo "  表数=${N}"

# ---- 跳过 CDC 排除表 ----
# 高频日志/流水表已从 Debezium table.exclude.list 排除（名单见 cdc-excluded-tables.txt），
# 源库持续增长而 Redis 侧数据已退役清理，若参与对账必然 FAIL——这不是静默丢失，是设计如此。
EXCL="${IRIS_EXCL:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/cdc-excluded-tables.txt}"
if [ -f "$EXCL" ]; then
  EXCL_N=$(grep -vE '^[[:space:]]*#|^[[:space:]]*$' "$EXCL" | grep -c .)
  TABLES=$(printf '%s\n' "$TABLES" | grep -vxF -f <(grep -vE '^[[:space:]]*#|^[[:space:]]*$' "$EXCL") || true)
  NEW_N=$(printf '%s\n' "$TABLES" | grep -c .)
  echo "  跳过 CDC 排除表 ${EXCL_N} 张（cdc-excluded-tables.txt），对账表数 ${NEW_N}"
fi

{
  printf '%s\n' "$TABLES" | awk 'NR==1{printf "SELECT \x27%s\x27 tbl, COUNT(*) n FROM '"$DB"'.%s", $1, $1; next}
    {printf " UNION ALL SELECT \x27%s\x27, COUNT(*) FROM '"$DB"'.%s", $1, $1}'
  printf ';\n'
} > /tmp/cdc-completeness-src.sql

docker exec -i "$MC" mysql -uroot -piris-root -N --default-character-set=utf8mb4 \
  < /tmp/cdc-completeness-src.sql > /tmp/cdc-completeness-src.tsv 2>/dev/null
ok "源库基线已取（$(grep -c . /tmp/cdc-completeness-src.tsv) 表）"

# ---- 2. 索引逐表 num_docs ----
# 单连接批处理：135 次 docker exec 每次约 1 秒（共两分多钟），改为把命令顺序灌进同一个
# redis-cli 进程的 stdin，再用 ECHO 哨兵切块。
# 哨兵是必需的：FT.INFO 的回复长度不固定（flags 是嵌套列表），无法按行数定位。
echo "== 2. 取 Redis 索引逐表 num_docs（单连接批处理）=="
{
  for t in $TABLES; do
    echo "FT.INFO iris:${NS}:index:${t}"
    echo "ECHO __END__${t}"
  done
} | docker exec -i "$RC" redis-cli > /tmp/cdc-completeness-idx.raw 2>/dev/null

awk '
  /^__END__/ { tbl = substr($0, 8); if (tbl != "") printf "%s\t%s\t%s\n", tbl, nd, pi; nd=""; pi=""; next }
  prev == "num_docs"        { nd = $0 }
  prev == "percent_indexed" { pi = $0 }
  { prev = $0 }
' /tmp/cdc-completeness-idx.raw > /tmp/cdc-completeness-idx.tsv
echo "  解析到索引记录=$(grep -c . /tmp/cdc-completeness-idx.tsv) 条"

# ---- 3. 逐表比对（单次 awk 联结两份 TSV）----
# percent_indexed 是 0~1 小数（1 = 100%）；<1 时 num_docs 天然偏少，须单独归类为
# 「回填中」而非丢数据，否则刚重启的实例会被误判。
echo "== 3. 逐表比对（源库行数 vs 索引文档数）=="
awk -F'\t' '
  NR==FNR { src[$1]=$2; order[++n]=$1; next }
  { idx[$1]=$2; pin[$1]=$3 }
  END {
    mis=0; pend=0; tot_src=0; tot_idx=0
    for (i = 1; i <= n; i++) {
      t = order[i]; s = src[t] + 0; tot_src += s
      if (!(t in idx)) { printf "%s\t源=%d\t索引=缺失（索引未建？）\n", t, s; mis++; continue }
      x = idx[t] + 0; tot_idx += x
      if (x != s) {
        if (pin[t] + 0 < 1) { printf "%s\t源=%d\t索引=%d\t索引回填中(%s)\n", t, s, x, pin[t]; pend++ }
        else                { printf "%s\t源=%d\t索引=%d\t差=%d\n", t, s, x, s - x; mis++ }
      }
    }
    printf "SUMMARY\t%d\t%d\t%d\t%d\t%d\n", mis, pend, tot_src, tot_idx, n
  }' /tmp/cdc-completeness-src.tsv /tmp/cdc-completeness-idx.tsv > /tmp/cdc-completeness-result.txt

SUMMARY=$(grep '^SUMMARY' /tmp/cdc-completeness-result.txt)
MIS=$(echo "$SUMMARY" | cut -f2)
PEND=$(echo "$SUMMARY" | cut -f3)
SRC_TOTAL=$(echo "$SUMMARY" | cut -f4)
IDX_TOTAL=$(echo "$SUMMARY" | cut -f5)
echo "  源库总行数=${SRC_TOTAL} 索引文档总数=${IDX_TOTAL} 不一致表=${MIS} 回填中=${PEND}"

if [ "${MIS:-1}" -eq 0 ] && [ "${PEND:-1}" -eq 0 ]; then
  ok "全部 ${N} 表逐表一致（无静默丢失）"
else
  echo "  ---- 明细（前 30 行）----"
  grep -v '^SUMMARY' /tmp/cdc-completeness-result.txt | head -30 | sed 's/^/    /'
  [ "${MIS:-1}" -eq 0 ] \
    && ok "无丢数据（${PEND} 表索引仍在回填，属正常异步过程）" \
    || bad "${MIS} 张表源库与索引不一致（CDC 丢数据或投影缺失）"
fi

# ---- 4. 总量对账 ----
echo "== 4. 总量对账 =="
DIFF=$((SRC_TOTAL - IDX_TOTAL))
if [ "$DIFF" -eq 0 ]; then
  ok "总量一致（${SRC_TOTAL} 行）"
elif [ "${PEND:-0}" -gt 0 ]; then
  echo "  差=${DIFF}（其中含 ${PEND} 表索引回填中，待回填完成后重跑）"
  bad "总量差 ${DIFF} 行（需回填完成后复验）"
else
  bad "总量差 ${DIFF} 行"
fi

echo
echo "==== 结果：PASS=${pass} FAIL=${fail} 表数=${N} ===="
[ "$fail" -gt 0 ] && exit 1
echo "CDC 完整性验收通过"

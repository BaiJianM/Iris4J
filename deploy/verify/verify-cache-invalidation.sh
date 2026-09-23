#!/usr/bin/env bash
# 校验目标：实体缓存失效采用「索引集合驱动」而非「SCAN 全 keyspace」。
#
# 校验两方面：
#   功能 —— 缓存条目被真实删除、索引集合同步清空、空索引路径零成本；
#   路径 —— 失效期间 Redis 的 smembers 调用增加（证明走了索引），scan 调用不增加
#           （证明不再整库遍历）。先测空窗期噪声作为基线，避免后台轮询误报。
#
# 为什么盯 commandstats：SCAN 的代价是「整库游标遍历」，MATCH 只在服务端过滤，
# 与是否命中无关。所以判定"是否还整库 SCAN"最直接的证据就是 scan 调用次数，
# 而不是耗时（耗时受数据量与负载波动影响，不可复现）。
#
# 用法：./deploy/verify/verify-cache-invalidation.sh
# 前置：redis-iris-java 已在 $IRIS_BASE 运行；iris-redis / iris-mysql 容器可用。
#
# 【先决条件：目标实体的 CDC 流必须已追平（lag=0）】
# 第 3 步要造缓存条目 + 索引成员，第 4 步验证「源库变更 → CDC 失效」。若该实体还有
# 积压，CDC 每消费一条事件就调一次 invalidateEntity，把 cacheidx:{entity} 连同成员
# 一起删掉——第 3 步「索引成员 >=5」必然失败，属**时序**问题而非实现缺陷。
# 故本脚本在 lag != 0 时直接 exit 2（环境未就绪），不产出假 FAIL。
# PEL 残留（pending>0）只警告不中止：lag=0 后残留的是上一实例的未确认消息，
# 只能靠 reclaim 慢速消化（约 1 条/5.7 秒），引起的是偶发失效而非洪流。
#
# 可覆盖的环境变量：
#   IRIS_ENTITY     目标实体（默认 ord_order）。换成任意已追平实体都能跑，例如
#                   IRIS_ENTITY=afc_after_sale。变更列与查询字段会自动从
#                   information_schema 推导（优先 remark/note/desc 列）。
#   IRIS_PK_COL     主键列（默认 id）
#   IRIS_UPDATE_COL 显式指定要改写的列；IRIS_FIELDS 显式指定查询字段
#
# 【必须在单实例下执行】多实例会从两个方向污染结论，第 0 步会硬性拦截：
#   1) 重复投影：消费端 id 带实例标识（cdc-{ns}-{table}-{host}-{pid}），
#      两个实例不再互抢 pending、不会再把正常消息误判成毒消息；但每条事件仍会被
#      **各投影一遍**（双写），Redis 数据与 DLQ 计数都不再代表单实例行为；
#   2) 代码版本漂移：IDEA 里常驻一个 Debug 实例，其 classpath 指向 */target/classes，
#      若不重启就仍是改动前的旧 class，断言会对着旧字节码跑出假结果。
# 注意：双实例并存会误判 FAIL——任一实例跑着旧字节码，都会同时污染
# 「数据条数」与「scan 增量」两项结论。
#
# 【另一类假失败来源：残留的 docker exec redis-cli 客户端】
# 宿主机的 `redis-cli --scan` / `MONITOR` 若忘了收尾（被 head 截断后仍持连接、
# 或后台跑着监控循环），会持续累加 SCAN 调用——噪声可达 2000 次/秒、
# 3 秒窗口 6166 次，把「scan 增量」这条基线彻底抬走，导致第 5 步假失败。
# 该连接的特征：来自容器内 127.0.0.1 且 cmd=scan|monitor（应用走 Lettuce、
# 地址是 192.168.65.1；Debezium 走 172.21.0.x）。第 0 步会拦截并给出 CLIENT KILL 命令。
set -uo pipefail

BASE="${IRIS_BASE:-http://localhost:8090}"
API_KEY="${IRIS_API_KEY:-legacy-k1}"
NS="${IRIS_NS:-ecomm}"
ENTITY="${IRIS_ENTITY:-ord_order}"
PK_COL="${IRIS_PK_COL:-id}"
RC="${IRIS_REDIS_CONTAINER:-iris-redis}"
MC="${IRIS_MYSQL_CONTAINER:-iris-mysql}"
DB="${IRIS_DB:-iris_demo}"

redis() { docker exec "$RC" redis-cli "$@"; }
mysqlq() { docker exec "$MC" mysql -uroot -piris-root -N --default-character-set=utf8mb4 -e "$1"; }

INDEX_KEY="iris:${NS}:cacheidx:${ENTITY}"

pass=0; fail=0
ok() { echo "  [PASS] $1"; pass=$((pass + 1)); }
bad() { echo "  [FAIL] $1"; fail=$((fail + 1)); }

# INFO commandstats 里某命令的累计调用次数（该命令从未被调用时记 0）
#
# 解析要点：行形如 cmdstat_smembers:calls=4,usec=1652,...，用 [:,=] 三者作分隔符，
# 于是第一个字段是「cmdstat_smembers」——不带尾冒号。比较值必须与之严格同形，
# 曾因误写成 "cmdstat_smembers:"（带冒号）导致恒返回 0，使 scan 断言假通过。
stat_calls() {
  redis INFO commandstats 2>/dev/null | tr -d '\r' \
    | awk -F'[:,=]' -v k="cmdstat_$1" '$1 == k { print $3; found = 1; exit } END { if (!found) print 0 }'
}

# 单连接批量 EXISTS：把 N 条 EXISTS 塞进同一条 redis-cli 连接。
#
# 为什么不逐条 docker exec：每次起进程有百毫秒级开销，N 条就是数秒，而缓存 TTL 默认
# 只有 60 秒（iris.cache.ttl-seconds）——慢速核对会让条目在检查途中自然过期，
# 被误读成「索引成员没有对应缓存条目」。逐条慢查会漏报过期前状态，必须批量核对。
exists_many() {
  local cmds="" m
  for m in "$@"; do cmds="${cmds}EXISTS iris:${NS}:cache:${m}\n"; done
  [ -z "$cmds" ] && return
  printf "$cmds" | docker exec -i "$RC" redis-cli 2>/dev/null | tr -d '\r' | paste -sd, -
}

# 统计给定「相对 key 段」列表中仍存活的条目数
alive_count() {
  local n=0 v
  for v in $(exists_many "$@" | tr ',' ' '); do
    [ "$v" = "1" ] && n=$((n + 1))
  done
  echo "$n"
}

echo "== 0. 前置检查 =="
if curl -s --noproxy '*' -m 5 "$BASE/actuator/health" | grep -q '"status":"UP"'; then
  ok "redis-iris-java 健康（$BASE）"
else
  bad "redis-iris-java 未就绪（$BASE/actuator/health）"
  echo "验收中止：请先启动应用"
  exit 1
fi

# 单实例守卫：每条流常驻一个阻塞在 XREADGROUP 的消费连接，故单实例的连接数 ≈ 流数（ecomm 为 135）。
# 一旦逼近 2 倍即说明存在第二个实例，此时下面的 scan/smembers 增量统计已不可信 —— 宁可中止也不出错结论。
XREAD_CONNS=$(redis CLIENT LIST 2>/dev/null | tr -d '\r' | grep -c 'cmd=xreadgroup')
if [ "${XREAD_CONNS:-0}" -gt 200 ]; then
  bad "检测到多个 redis-iris-java 实例（XREADGROUP 连接=${XREAD_CONNS}，单实例应 ≈135）"
  echo "原因：每条事件会被两个实例各投影一遍（双写），且任一实例跑着旧字节码时，"
  echo "      会同时污染「数据条数」与「scan 增量」两项结论。"
  echo "验收中止：请只保留一个实例（jps -l 可列出全部 redis-iris-java 进程）后重试"
  exit 1
fi
ok "单实例确认（XREADGROUP 连接=${XREAD_CONNS}）"

# 残留客户端守卫：宿主机 `docker exec redis-cli` 的 scan/monitor 若未收尾，会持续累加
# SCAN 调用把噪声基线抬走（可达 2000 次/秒），使 scan 增量断言假失败或假通过。
# 特征：来自容器内 127.0.0.1 且 cmd=scan 或 monitor——应用（Lettuce）来自 192.168.65.1，
# Debezium 来自 172.21.0.x，故此过滤不会误伤正常客户端。
STRAY=$(redis CLIENT LIST 2>/dev/null | tr -d '\r' \
  | grep 'addr=127\.0\.0\.1' | grep -Ec 'cmd=(scan|monitor)')
if [ "${STRAY:-0}" -gt 0 ]; then
  bad "检测到残留扫描客户端 ${STRAY} 个（cmd=scan/monitor，来自容器内 127.0.0.1）"
  echo "影响：它会持续累加 SCAN 调用，使「scan 增量」统计失真（假失败或假通过）。"
  echo "处置：docker exec iris-redis redis-cli CLIENT LIST | grep 127.0.0.1 找到 id，"
  echo "      再执行 docker exec iris-redis redis-cli CLIENT KILL ID <id>"
  exit 1
fi
ok "无残留扫描客户端（scan/monitor 均为 0）"

# 先决条件守卫：目标实体的 CDC 流必须已追平（lag=0）。
#
# 为什么必须挡：本脚本第 3 步要造 5 条缓存条目 + 索引成员，再在第 4 步验证
# 「源库变更 → CDC 失效」。若该实体的流还有积压，CDC 每消费一条事件就调一次
# invalidateEntity → 把整张 cacheidx:{entity} 连同成员一起删掉，于是第 3 步
# 「索引集合已登记成员 数量>=5」必然失败——这是**时序**问题，不是失效逻辑缺陷。
# 排空期针对仍有积压的实体（如 lag 数万）跑本脚本会在此项假失败。
# 故宁可中止并明说，也不让"环境未就绪"伪装成"实现有 bug"。
#
# 【pending 为什么只警告不中止】lag=0 后仍可能残留 PEL（上一实例被停时未确认的消息，
# 挂在已死消费端名下，PEL 剪枝硬约束）。这些只能靠 reclaim 消化，速率约为
# 每条流每轮 sweep 100 条、一轮 sweep 约 9.5 分钟（RECLAIM_BATCH=100、135 流串行）
# ≈ 1 条/5.7 秒。这个速率下 reclaim 引起的失效是"偶发"而非"洪流"，第 3 步的两秒
# 窗口通常能存活，故降级为提示；若第 3 步确实失败，日志会明示可能是 reclaim 干扰。
LAG_JSON=$(redis XINFO GROUPS "iris.iris_demo.${ENTITY}" 2>/dev/null | tr -d '\r')
G_LAG=$(printf '%s\n' "$LAG_JSON" | awk '/^lag$/{getline; print; exit}')
G_PEN=$(printf '%s\n' "$LAG_JSON" | awk '/^pending$/{getline; print; exit}')
if [ -z "${G_LAG:-}" ]; then
  echo "  [SKIP] 读不到 ${ENTITY} 的 group 信息（流或 group 不存在），无法确认是否已追平"
  echo "验收中止（exit 2）：先确认 CDC 已为该实体建流建组"
  exit 2
fi
if [ "$G_LAG" != "0" ]; then
  echo "  [SKIP] ${ENTITY} 的 CDC 尚未追平（lag=${G_LAG} pending=${G_PEN:-?}）"
  echo "影响：CDC 会持续高频失效该实体缓存索引，第 3 步的索引成员断言必然假失败。"
  echo "处置：等排空完成再跑（./deploy/verify/monitor-cdc-import.sh 60 60），"
  echo "      或指定一个已追平的实体（IRIS_ENTITY=<entity>）。"
  echo "验收中止（exit 2）：环境未就绪，不代表实现有缺陷"
  exit 2
fi
if [ "${G_PEN:-0}" != "0" ]; then
  echo "  [NOTE] ${ENTITY} 已追平但 PEL 仍有 ${G_PEN} 条（上一实例的未确认消息，挂在死消费端名下）"
  echo "         它只能靠 reclaim 慢速消化（约 1 条/5.7 秒），会偶发触发失效；"
  echo "         若第 3 步「索引成员 >=5」失败，先怀疑这个而不是失效逻辑。"
fi
ok "目标实体已追平（${ENTITY} lag=0 pending=${G_PEN:-0}，失效断言可定位）"

echo "== 1. 清理基线（按索引成员清单删，不做全库 SCAN） =="
for member in $(redis SMEMBERS "$INDEX_KEY" 2>/dev/null | tr -d '\r'); do
  redis DEL "iris:${NS}:cache:${member}" >/dev/null 2>&1
done
redis DEL "$INDEX_KEY" >/dev/null 2>&1
ok "清理完成（索引存在=$(redis EXISTS "$INDEX_KEY" 2>/dev/null)）"

echo "== 2. 空窗期噪声基线（3 秒内后台 SCAN 增量） =="
scan0=$(stat_calls scan)
sleep 3
scan1=$(stat_calls scan)
NOISE=$((scan1 - scan0))
echo "  后台 scan 增量 = $NOISE"

echo "== 2b. 推导实体相关列名（脚本对任意实体可复用） =="
# 变更列的选取（按优先级）：
#   1) IRIS_UPDATE_COL 显式指定；
#   2) 名字含 remark/note/desc 的字符串列（业务上就是"自由文本"，改它最安全，
#      ord_order 会命中 buyer_remark——与脚本原先硬编码的列一致）；
#   3) 兜底取第一个字符串列。
# 为什么不用「IS_NULLABLE='YES'」筛：本库（ecomm 数据集）**所有列都是 NOT NULL**，
# 按可空筛会一条也选不到，反而把原本验证通过的 ord_order 路径变成"跳过变更用例"。
# 写成随机值对 NOT NULL 列同样合法，故只需保证不改到业务标识列。
UPDATE_COL="${IRIS_UPDATE_COL:-}"
if [ -z "$UPDATE_COL" ]; then
  UPDATE_COL=$(mysqlq "SELECT COLUMN_NAME FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='${DB}' AND TABLE_NAME='${ENTITY}'
      AND DATA_TYPE IN ('varchar','char','text','mediumtext')
      AND (COLUMN_NAME LIKE '%remark%' OR COLUMN_NAME LIKE '%note%' OR COLUMN_NAME LIKE '%desc%')
    ORDER BY ORDINAL_POSITION LIMIT 1;" 2>/dev/null | tr -d ' \r')
fi
if [ -z "$UPDATE_COL" ]; then
  UPDATE_COL=$(mysqlq "SELECT COLUMN_NAME FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='${DB}' AND TABLE_NAME='${ENTITY}'
      AND DATA_TYPE IN ('varchar','char','text','mediumtext')
      AND COLUMN_NAME <> '${PK_COL}'
    ORDER BY ORDINAL_POSITION LIMIT 1;" 2>/dev/null | tr -d ' \r')
  [ -n "$UPDATE_COL" ] && echo "  [WARN] 无 remark/note/desc 列，改用 ${UPDATE_COL}（可能覆盖业务值）"
fi
# 查询字段：默认与原先一致（主键 + 该表第一个字符串列），ord_order 即 id,order_no
if [ -n "${IRIS_FIELDS:-}" ]; then
  FIELDS_JSON=$(printf '%s' "$IRIS_FIELDS" | awk -F, '{for(i=1;i<=NF;i++){printf "%s\"%s\"", (i>1?",":""), $i}}')
else
  F1=$(mysqlq "SELECT COLUMN_NAME FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA='${DB}' AND TABLE_NAME='${ENTITY}'
      AND DATA_TYPE IN ('varchar','char','text','mediumtext')
    ORDER BY ORDINAL_POSITION LIMIT 1;" 2>/dev/null | tr -d ' \r')
  FIELDS_JSON="\"${PK_COL}\",\"${F1}\""
fi
echo "  查询字段=[${FIELDS_JSON}] 变更列=${UPDATE_COL:-未推导出} 主键列=${PK_COL}"

echo "== 3. 发多次查询，产生多条缓存条目与索引成员 =="
# 必须造出 N>1：只有 1 条时「全部清掉」与「只清掉一条」不可区分，漏删无法暴露。
for qpage in 1 2 3 4 5; do
  curl -s --noproxy '*' -m 20 -X POST "$BASE/api/v1/entities/${ENTITY}/query" \
    -H 'Content-Type: application/json' -H "X-API-Key: ${API_KEY}" \
    -d "{\"namespace\":\"${NS}\",\"fields\":[${FIELDS_JSON}],\"page\":${qpage},\"pageSize\":5}" \
    -o /tmp/t25-query.json 2>/dev/null
done
if grep -q '"total"' /tmp/t25-query.json 2>/dev/null; then
  ok "查询成功（缓存应已写入）"
else
  bad "查询失败：$(head -c 160 /tmp/t25-query.json 2>/dev/null)"
fi

MEMBERS=$(redis SMEMBERS "$INDEX_KEY" 2>/dev/null | tr -d '\r')
MEMBER_COUNT=$(printf '%s\n' "$MEMBERS" | grep -c .)
if [ "${MEMBER_COUNT:-0}" -ge 5 ]; then
  ok "索引集合已登记成员 数量=${MEMBER_COUNT}"
else
  bad "索引成员偏少（${MEMBER_COUNT}，期望 >=5）：缓存写入未完整登记索引"
fi

alive=$(alive_count $MEMBERS)
if [ "$alive" -ge 1 ] && [ "$alive" -eq "${MEMBER_COUNT:-0}" ]; then
  ok "索引成员与存活缓存条目一一对应（${alive}/${MEMBER_COUNT}）"
else
  bad "索引成员与存活缓存不一致（存活 ${alive} / 成员 ${MEMBER_COUNT}）"
fi

echo "== 4. 触发源库变更 -> CDC 失效 -> 校验 =="
PK=$(mysqlq "SELECT ${PK_COL} FROM ${DB}.${ENTITY} ORDER BY ${PK_COL} LIMIT 1;" 2>/dev/null | tr -d ' \r')
if [ -z "$PK" ] || [ -z "$UPDATE_COL" ]; then
  bad "取不到样本主键或可更新列（pk=${PK:-空} col=${UPDATE_COL:-空}），跳过变更用例"
else
  SCAN_B=$(stat_calls scan)
  SMEM_B=$(stat_calls smembers)

  mysqlq "UPDATE ${DB}.${ENTITY} SET ${UPDATE_COL}='VERIFY-MARK-$RANDOM' WHERE ${PK_COL}=${PK};" >/dev/null 2>&1
  echo "  已更新 ${ENTITY}.${PK_COL}=${PK}（列 ${UPDATE_COL}），等待 CDC 消费..."

  cleared=0
  for _ in $(seq 1 30); do
    if [ "$(redis EXISTS "$INDEX_KEY" 2>/dev/null)" = "0" ]; then cleared=1; break; fi
    sleep 1
  done

  SCAN_A=$(stat_calls scan)
  SMEM_A=$(stat_calls smembers)
  SCAN_D=$((SCAN_A - SCAN_B))
  SMEM_D=$((SMEM_A - SMEM_B))

  if [ "$cleared" = "1" ]; then ok "索引已被 CDC 失效清除"; else bad "等待 30s 索引仍未清除"; fi

  leftover=$(alive_count $MEMBERS)
  if [ "$leftover" -eq 0 ]; then
    ok "原缓存条目已全部删除（${MEMBER_COUNT} 条全清）"
  else
    bad "仍有 ${leftover}/${MEMBER_COUNT} 条缓存残留（只清了部分？）"
  fi

  if [ "$SMEM_D" -ge 1 ]; then
    ok "失效走索引路径（smembers 增量=${SMEM_D}）"
  else
    bad "未观察到 smembers 调用（增量=${SMEM_D}），失效可能未走索引"
  fi

  if [ "$SCAN_D" -le "$NOISE" ]; then
    ok "失效未触发额外 SCAN（增量=${SCAN_D}，噪声基线=${NOISE}）"
  else
    bad "失效仍触发 SCAN（增量=${SCAN_D} > 噪声基线 ${NOISE}）"
  fi
fi

echo "== 5. 空索引快速路径（无缓存时再变更，应零成本且不报错） =="
if [ -n "${PK:-}" ]; then
  SCAN_B2=$(stat_calls scan)
  mysqlq "UPDATE ${DB}.${ENTITY} SET ${UPDATE_COL}='VERIFY-MARK2-$RANDOM' WHERE ${PK_COL}=${PK};" >/dev/null 2>&1
  sleep 3
  SCAN_A2=$(stat_calls scan)
  D2=$((SCAN_A2 - SCAN_B2))
  if [ "$D2" -le "$NOISE" ]; then
    ok "空索引路径无 SCAN（增量=${D2}）"
  else
    bad "空索引路径仍 SCAN（增量=${D2}）"
  fi
  if [ "$(redis EXISTS "$INDEX_KEY" 2>/dev/null)" = "0" ]; then
    ok "索引保持为空（无脏残留）"
  else
    bad "索引被意外重建"
  fi
fi

echo
echo "==== 结果：PASS=${pass} FAIL=${fail} ===="
if [ "$fail" -gt 0 ]; then
  exit 1
fi
echo "验收通过：缓存失效索引化"

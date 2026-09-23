#!/usr/bin/env bash
# 校验目标：语义缓存路径为 O(1)——不退化为「SCAN 全 keyspace」。
#
# 背景：语义缓存要按前缀列出候选条目（{entity}:sem:{fp}:{hard}:*）。若读取路径
# 跑 SCAN MATCH，而 SCAN 是整库游标遍历，
# MATCH 只在服务端过滤、不减少遍历量，代价 O(全 keyspace 键数)，与是否命中无关。
# 现复用 iris:{ns}:cacheidx:{entity} 索引 + 客户端前缀过滤，并补 MGET
# 把 N 次 GET 压成一次往返。
#
# ---------------------------------------------------------------------------
# 【为什么默认用「探针实体」而不是 ord_order】
# 若目标实体存在 CDC 流且还在排空积压，每消费一条事件就调一次
# invalidateEntity(ecomm, ord_order) → 把整张 cacheidx:ord_order 连同成员一起删掉。
# 也就是说，在前面的流没排空之前，脚本刚写进去的 4 个成员会被 CDC 立刻清掉，
# 校验结果会假 FAIL——这是时序而非缺陷。
# CacheController 对 namespace/entity 不做存在性校验，故改用「没有对应 CDC 流」
# 的探针实体 t28probe：走的仍是同一条代码路径（{entity}:sem:* →
# getAllMatchingWithKeys），但不会被任何 CDC 事件失效，校验结果与排空进度解耦。
#
# 【判定标准为什么用 commandstats 计数而非耗时】
# 耗时受负载波动影响不可复现，而「有没有 SCAN」是二值事实。
#
# 【哪些计数可信、哪些会被 CDC 污染——务必分清】
#   scan    ：可信。CDC 失效路径不再 SCAN（残留客户端守卫见缓存失效脚本）。
#             DS 必须为 0，这是本脚本的核心断言。
#   mget    ：可信。CDC 路径（invalidateEntity）只做 SMEMBERS + DEL，不调 MGET。
#             故 DG ≥ 1 只可能来自索引批量取值。
#   smembers：**排空期不可信**。CDC 每消费一条事件就 SMEMBERS 一次
#             （invalidateEntity 首行动作），排空时会看到几百上千的增量
#             （约 547/次），与本脚本无关。故仅在 CDC 空闲（全局 lag=0）时
#             才作为断言，否则降级为提示。
#
# 用法： ./deploy/verify/verify-semantic-cache-index.sh
# 前置：iris4j 已在 $IRIS_BASE 运行；iris-redis 容器可用；单实例。
# 可用 IRIS_ENTITY 覆盖探针实体（覆盖成有 CDC 流的实体时，请等排空完成再跑）。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE="${IRIS_BASE:-http://localhost:8090}"
API_KEY="${IRIS_API_KEY:-legacy-k1}"
NS="${IRIS_NS:-ecomm}"
E="${IRIS_ENTITY:-t28probe}"
RC="${IRIS_REDIS_CONTAINER:-iris-redis}"
YML="${IRIS_YML:-$HERE/../../api/src/main/resources/application.yml}"

IDX="iris:${NS}:cacheidx:${E}"
pass=0; fail=0; warn=0
ok()   { echo "  [PASS] $1"; pass=$((pass + 1)); }
bad()  { echo "  [FAIL] $1"; fail=$((fail + 1)); }
note() { echo "  [NOTE] $1"; warn=$((warn + 1)); }

redis() { docker exec "$RC" redis-cli "$@"; }

# 与 verify-cache-invalidation.sh 同一套解析：cmdstat_xxx:calls=N，
# 用 [:,=] 作分隔符后首字段是「cmdstat_xxx」（不带尾冒号）。
stat_calls() {
  redis INFO commandstats 2>/dev/null | tr -d '\r' \
    | awk -F'[:,=]' -v k="cmdstat_$1" '$1 == k { print $3; found = 1; exit } END { if (!found) print 0 }'
}

mkbody() {
  printf '{"namespace":"%s","key":"%s","value":{"filtersText":"%s","vector":[0.1,0.2,0.3],"result":{"items":[],"total":0,"page":1,"pageSize":5},"fingerprint":"qwen3-embedding-0.6b-d1024","createdAt":"2026-09-10T13:00:00Z"},"ttlSeconds":300}' \
    "$NS" "$1" "$2"
}

# 全局 CDC 是否空闲：读一次 EVAL 汇总 lag/pending（复用 stream-stats.lua）
cdc_idle() {
  local lua="$HERE/stream-stats.lua"
  [ -f "$lua" ] || { echo "unknown"; return; }
  local keys=() k
  while IFS= read -r k; do keys+=("$k"); done \
    < <(grep -oE 'stream: iris\.iris_demo\.[a-z0-9_]+' "$YML" 2>/dev/null | awk '{print $2}')
  [ "${#keys[@]}" -eq 0 ] && { echo "unknown"; return; }
  local out lag pen unk
  out=$(docker exec "$RC" redis-cli EVAL "$(cat "$lua")" "${#keys[@]}" "${keys[@]}" 2>&1 \
        | grep '^TOTAL')
  lag=$(echo "$out" | awk -F'|' '{print $4}')
  pen=$(echo "$out" | awk -F'|' '{print $3}')
  unk=$(echo "$out" | grep -oE 'unknown=[0-9]+' | cut -d= -f2)
  if [ "${unk:-1}" = "0" ] && [ "${lag:-1}" = "0" ] && [ "${pen:-1}" = "0" ]; then
    echo "yes"
  else
    echo "no"
  fi
}

echo "== 0. 前置 =="
if curl -s --noproxy '*' -m 5 "$BASE/actuator/health" | grep -q '"status":"UP"'; then
  ok "iris4j 健康"
else
  bad "iris4j 未就绪（$BASE）"
  exit 1
fi

XREAD_CONNS=$(redis CLIENT LIST 2>/dev/null | tr -d '\r' | grep -c 'cmd=xreadgroup')
if [ "${XREAD_CONNS:-0}" -gt 200 ]; then
  bad "检测到多实例（XREADGROUP 连接=${XREAD_CONNS}），scan 增量不可信，中止"
  exit 1
fi
ok "单实例确认（XREADGROUP 连接=${XREAD_CONNS}）"

# 探针实体必须没有对应 CDC 流，否则会被 CDC 失效清掉（见文件头说明）
if grep -qE "stream: iris\.iris_demo\.${E}\b" "$YML" 2>/dev/null; then
  note "实体 ${E} 存在 CDC 流，验收结果会受排空进度影响（建议用无流实体，如 t28probe）"
  CDC_MODE="loaded"
else
  ok "探针实体 ${E} 无对应 CDC 流（验收与排空进度解耦）"
  CDC_MODE="probe"
fi
IDLE=$(cdc_idle)
echo "  CDC 全局状态：idle=${IDLE:-unknown}（smembers 断言是否可信见文件头）"

echo "== 1. 造数据：3 条 ${E}:sem:* + 1 条干扰 ${E}:precise:* =="
redis DEL "$IDX" >/dev/null 2>&1
for suffix in aa:bb:c1 aa:bb:c2 zz:yy:c3; do
  curl -s --noproxy '*' -m 20 -X PUT "$BASE/api/v1/cache" \
    -H 'Content-Type: application/json' -d "$(mkbody "${E}:sem:${suffix}" "SEM-MARK")" -o /dev/null
done
curl -s --noproxy '*' -m 20 -X PUT "$BASE/api/v1/cache" \
  -H 'Content-Type: application/json' -d "$(mkbody "${E}:precise:1" "NOISE-MARK")" -o /dev/null

# indexedKey 登记是同步的（put 内 SADD），但仍给一点容错窗口——
# 若 CDC/GC 正在摘成员，多轮采样能区分「真为空」与「刚被清掉」
MEMBER_COUNT=0
for _ in 1 2 3 4 5 6 7 8 9 10; do
  MEMBERS=$(redis SMEMBERS "$IDX" 2>/dev/null | tr -d '\r')
  MEMBER_COUNT=$(printf '%s\n' "$MEMBERS" | grep -c .)
  [ "${MEMBER_COUNT:-0}" -ge 4 ] && break
  sleep 1
done
if [ "${MEMBER_COUNT:-0}" -ge 4 ]; then
  ok "索引已登记 ${MEMBER_COUNT} 个成员（3 sem + 1 noise）"
else
  if [ "$CDC_MODE" = "loaded" ]; then
    bad "索引成员=${MEMBER_COUNT}（期望 ≥4）：实体有 CDC 流且正在失效，属时序问题——请等排空后重跑"
  else
    bad "索引成员=${MEMBER_COUNT}（期望 ≥4）：探针实体无 CDC，索引仍为空则 put 未登记索引（真缺陷）"
  fi
fi

echo "== 2. 触发 semantic/reindex，观察命令标准 =="
S0=$(stat_calls scan); M0=$(stat_calls smembers); G0=$(stat_calls mget)
T0=$(date +%s)
RESP=$(curl -s --noproxy '*' -m 60 -X POST \
  "$BASE/api/v1/cache/semantic/reindex?namespace=${NS}&entity=${E}")
T1=$(date +%s)
S1=$(stat_calls scan); M1=$(stat_calls smembers); G1=$(stat_calls mget)
DS=$((S1 - S0)); DM=$((M1 - M0)); DG=$((G1 - G0))
echo "  resp   : $RESP"
echo "  耗时   : $((T1 - T0)) 秒"
echo "  增量   : scan=${DS} smembers=${DM} mget=${DG}"

# scanned 必须等于 3（只认 :sem: 段），证明客户端前缀过滤真的生效而非"全实体都扫"
SCANNED=$(echo "$RESP" | grep -oE '"scanned":[0-9]+' | cut -d: -f2)
if [ "${SCANNED:-0}" -eq 3 ]; then
  ok "前缀过滤生效（scanned=3，未把 precise:1 纳入）"
else
  bad "scanned=${SCANNED}（期望 3）：前缀过滤未生效或候选集不对（索引成员=${MEMBER_COUNT}）"
fi

if [ "$DS" -eq 0 ]; then
  ok "未触发任何 SCAN（增量=0，核心断言）"
else
  bad "仍触发 SCAN（增量=${DS}）：语义读取路径未走索引"
fi

if [ "$DG" -ge 1 ]; then
  ok "批量取值（mget 增量=${DG}，已把 N 次 GET 压成一次往返）"
else
  bad "未观察到 mget（增量=${DG}）：候选读取仍在逐条 GET，或候选集为空"
fi

# smembers 只在 CDC 空闲时可定位于该次 reindex；排空期 CDC 会把它抬高到几百，判不准
if [ "$DM" -ge 1 ]; then
  if [ "$IDLE" = "yes" ]; then
    ok "走索引集合（smembers 增量=${DM}，CDC 空闲故可定位）"
  else
    note "smembers 增量=${DM}，但 CDC 未空闲（每事件一次 SMEMBERS），无法定位本次 reindex——以 mget 增量为准"
  fi
else
  bad "未观察到 smembers（增量=${DM}）"
fi

echo "== 3. 清理 =="
for k in "${E}:sem:aa:bb:c1" "${E}:sem:aa:bb:c2" "${E}:sem:zz:yy:c3" "${E}:precise:1"; do
  redis DEL "iris:${NS}:cache:${k}" >/dev/null 2>&1
done
redis DEL "$IDX" >/dev/null 2>&1
if [ "$(redis EXISTS "$IDX" 2>/dev/null | tr -d '\r')" = "0" ]; then
  ok "索引已清空（无脏残留）"
else
  bad "索引未清空"
fi

echo
echo "==== 结果：PASS=${pass} FAIL=${fail} NOTE=${warn} ===="
[ "$fail" -gt 0 ] && exit 1
echo "验收通过：语义缓存索引路径"

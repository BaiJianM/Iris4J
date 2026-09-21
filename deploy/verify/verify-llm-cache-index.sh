#!/usr/bin/env bash
# 校验目标：LLM 缓存「维护路径」已索引化——请求路径零 SCAN。
#
# 背景：LlmCacheService.store() 在每次回答后无条件调 repository.count()，而旧实现是
#   SCAN MATCH iris:{ns}:llmcache:*:*  → 代价 O(全 keyspace 键数)，与命中几条无关。
#   366 万键下需 7.08 秒，且挂在请求路径上（表现为「答案逐字输出结束后卡几秒才出
#   用量统计」——footer 只在 done 帧渲染）。
# 现为两个 ZSET 索引（score = 过期 epoch 毫秒）：
#   iris:{ns}:llmcache:idx     成员 = 文档 key
#   iris:{ns}:llmcache:vecidx  成员 = 向量 key
#   count → ZCARD(O(1))、evictTo → ZRANGE(O(logN)) 取「最先过期」、
#   向量清理在索引内按后两段后缀匹配（不再 SCAN）。
#
# ---------------------------------------------------------------------------
# 【标准：commandstats 是全局计数，哪些可信必须分清】
#   scan             ：可信，且是本脚本核心断言。CDC 失效路径不 SCAN；本脚本
#                      自己会触发 SCAN 的地方只有 clear()（DELETE 接口），故快照 A
#                      刻意取在 DELETE 之后 → 之后任何 scan 增量都只能是请求路径产生的。
#   zadd             ：可信。只有 save() 登记索引会调（文档 + 向量各一次）。
#   zcard            ：可信。只有 count() / evictTo() 会调。
#   zrange           ：可信。evictTo 取受害者 + removeVectorsBySuffixes 枚举向量。
#   zremrangebyscore ：可信。只有 purgeExpired() 惰性清理会调。
#   zrem             ：可信。只有淘汰路径回删索引成员会调。
#   del              ：**不可信**（多处路径都在用），仅作提示。
#
# 【为什么用探针命名空间 t31probe 而不是 ecomm】
#   与语义缓存脚本同理：ecomm 的 CDC 若还在排空，其它路径会持续制造噪声；探针命名空间
#   不参与任何 CDC 流，结果与排空进度解耦。llm-cache 接口对 namespace 不做存在性
#   校验，走的仍是同一条 store() 代码路径。
#
# 【淘汰断言为什么可能被跳过】
#   max-entries 默认 1000，写 5 条不触发淘汰。脚本按配置自适应：
#     max-entries < 20 → 探针条数 N = max-entries + 3（必然触发淘汰）
#     max-entries ≥ 20 → 探针条数 N = 5，淘汰断言降级为 NOTE
#   要完整覆盖淘汰（ZRANGE/ZREM/向量清理）请这样起应用：
#     java -jar api/target/iris-lite-api-*.jar --iris.llm-cache.max-entries=3
#
# 用法： ./deploy/verify/verify-llm-cache-index.sh
#       若应用起在 8080： IRIS_BASE=http://localhost:8080 ./deploy/verify/verify-llm-cache-index.sh
# 前置：iris-lite 运行中；iris-redis 容器可用；**单实例**。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE="${IRIS_BASE:-http://localhost:8090}"
API_KEY="${IRIS_API_KEY:-legacy-k1}"
NS="${IRIS_NS:-t31probe}"
RC="${IRIS_REDIS_CONTAINER:-iris-redis}"

IDX="iris:${NS}:llmcache:idx"
VIDX="iris:${NS}:llmcache:vecidx"
pass=0; fail=0; warn=0
ok()   { echo "  [PASS] $1"; pass=$((pass + 1)); }
bad()  { echo "  [FAIL] $1"; fail=$((fail + 1)); }
note() { echo "  [NOTE] $1"; warn=$((warn + 1)); }
hdr()  { echo; echo "== $1"; }

# 注意：--noproxy 的值必须原样传；写成变量再展开会被路径扩展吃掉（* 变文件列表）
curlq() { curl -s --noproxy '*' "$@"; }
redis() { docker exec "$RC" redis-cli "$@"; }

stat_calls() {
  redis INFO commandstats 2>/dev/null | tr -d '\r' \
    | awk -F'[:,=]' -v k="cmdstat_$1" '$1 == k { print $3; found = 1; exit } END { if (!found) print 0 }'
}

# ---------------------------------------------------------------- 0. 守卫
hdr "0. 环境守卫"

xrg=$(redis CLIENT LIST 2>/dev/null | grep -c 'cmd=xreadgroup')
if [ "${xrg:-0}" -gt 200 ]; then
  bad "检测到多个 iris-lite 实例（XREADGROUP 连接=${xrg}，单实例≈135）——多实例会互抢 CDC pending，中止"
  echo; echo "汇总：PASS=$pass FAIL=$fail NOTE=$warn"; exit 2
fi
ok "单实例（XREADGROUP 连接=${xrg}）"

stray=$(redis CLIENT LIST 2>/dev/null | awk -F'[ =]' '
  { for (i = 1; i < NF; i++) if ($i == "cmd" && ($(i+1) == "scan" || $(i+1) == "monitor")) { print; break } }' | wc -l | tr -d ' ')
if [ "${stray:-0}" -gt 0 ]; then
  note "存在 ${stray} 个 cmd=scan|monitor 的残留客户端，可能抬高 SCAN 基线（处置：CLIENT KILL ID <id>）"
else
  ok "无残留 scan/monitor 客户端"
fi

stats=$(curlq -H "X-API-Key: $API_KEY" "$BASE/api/v1/llm-cache/stats?namespace=$NS")
if [ -z "${stats:-}" ]; then
  bad "应用无响应或未暴露 llm-cache 接口：$BASE —— 先起 iris-lite 再跑本脚本"
  echo; echo "汇总：PASS=$pass FAIL=$fail NOTE=$warn"; exit 2
fi
ok "应用可达：$BASE"

# ------------------------------------------------- 1. 读配置 + 建立干净基线
hdr "1. 读 max-entries 并清空探针命名空间"

MAXE=$(printf '%s' "$stats" | sed -n 's/.*"maxEntries":\([0-9]*\).*/\1/p')
[ -n "${MAXE:-}" ] || MAXE=1000
echo "  max-entries=$MAXE  探针命名空间=$NS"

if [ "$MAXE" -ge 20 ]; then
  N=5
  note "max-entries=$MAXE ≥ 20，写 $N 条不触发淘汰 → 淘汰断言降级；完整验收请用 --iris.llm-cache.max-entries=3 起应用"
else
  N=$((MAXE + 3))
  echo "  max-entries 较小，探针条数 N=$N（必然触发淘汰）"
fi

# clear 会 SCAN（运维路径刻意保留）——正因如此，快照 A 必须取在它之后
clr=$(curlq -X DELETE -H "X-API-Key: $API_KEY" "$BASE/api/v1/llm-cache?namespace=$NS")
echo "  清空探针命名空间：$clr"
ok "基线已建立（DELETE 之后取快照，故后续 scan 增量只可能来自请求路径）"

snap() { echo "$(stat_calls scan) $(stat_calls zadd) $(stat_calls zcard) $(stat_calls zrange) $(stat_calls zrem) $(stat_calls zremrangebyscore)"; }
read -r scanA zaddA zcardA zrangeA zremA zrrbA <<<"$(snap)"

# ------------------------------------------------------------- 2. 写探针条目
hdr "2. 写入 $N 条探针条目（PUT /api/v1/llm-cache，直接打 store()，不调真实 LLM）"

i=0
while [ "$i" -lt "$N" ]; do
  body=$(printf '{"namespace":"%s","prompt":"t31 探针问题 %d 号：今天有多少笔订单","response":"t31 探针答案 %d","model":"t31-probe-model","ttlSeconds":600}' \
    "$NS" "$i" "$i")
  resp=$(curlq -X PUT -H "X-API-Key: $API_KEY" -H 'Content-Type: application/json' \
    -d "$body" "$BASE/api/v1/llm-cache")
  case "$resp" in
    *'"stored":true'*) ;;
    *) bad "第 $i 条写入失败：$resp"; break ;;
  esac
  i=$((i + 1))
done
[ "$i" -eq "$N" ] && ok "$N 条全部写入成功"

read -r scanB zaddB zcardB zrangeB zremB zrrbB <<<"$(snap)"
Dscan=$((scanB - scanA)); Dzadd=$((zaddB - zaddA)); Dzcard=$((zcardB - zcardA))
Dzrange=$((zrangeB - zrangeA)); Dzrem=$((zremB - zremA)); Dzrrb=$((zrrbB - zrrbA))

# --------------------------------------------------------- 3. 核心：零 SCAN
hdr "3. 请求路径核心断言（commandstats 增量）"
echo "  Δscan=$Dscan Δzadd=$Dzadd Δzcard=$Dzcard Δzrange=$Dzrange Δzrem=$Dzrem Δzremrangebyscore=$Dzrrb"

if [ "$Dscan" -eq 0 ]; then
  ok "写入路径 scan 增量为 0（核心断言：不再有全库 SCAN）"
else
  bad "写入路径产生了 $Dscan 次 SCAN —— 索引化未生效（可能跑的是旧字节码/旧 jar，或另有 SCAN 路径）"
fi
[ "$Dzadd" -ge "$N" ] && ok "zadd 增量=$Dzadd ≥ $N（索引登记生效）" || bad "zadd 增量=$Dzadd < $N，索引登记缺失"
[ "$Dzcard" -ge "$N" ] && ok "zcard 增量=$Dzcard ≥ $N（count() 走 ZCARD）" || bad "zcard 增量=$Dzcard < $N，count() 未走索引"
if [ "$Dzrrb" -ge 1 ]; then
  ok "zremrangebyscore 增量=$Dzrrb（惰性清理过期副本已执行）"
else
  note "zremrangebyscore 增量为 0（无过期成员时也应被调用——请查 purgeExpired 是否被短路）"
fi

# --------------------------------------------------------- 4. 容量与淘汰
hdr "4. 索引容量与淘汰语义"
idxCard=$(redis ZCARD "$IDX" 2>/dev/null | tr -d '\r'); idxCard=${idxCard:-0}
expect=$((N < MAXE ? N : MAXE))
echo "  ZCARD $IDX = ${idxCard}（期望 $expect）"
[ "${idxCard}" -eq "$expect" ] && ok "索引基数 == min(写入数, max-entries) = $expect" \
  || bad "索引基数 ${idxCard} ≠ 期望 $expect"

if [ "$MAXE" -lt "$N" ]; then
  evicted=$((N - MAXE))
  [ "$Dzrange" -ge 1 ] && ok "zrange 增量=$Dzrange（淘汰按「最先过期」取受害者）" || bad "zrange 增量为 0，淘汰未走索引"
  [ "$Dzrem" -ge 1 ] && ok "zrem 增量=$Dzrem（索引成员已回删）" || bad "zrem 增量为 0，淘汰后索引残留"
  ok "本轮淘汰条数 = $evicted（$N 写入 - $MAXE 上限）"
else
  note "未触发淘汰（max-entries=$MAXE 未超限）；Δzrange=$Dzrange Δzrem=$Dzrem"
fi

# --------------------------------------------------------- 5. 索引一致性
hdr "5. 索引一致性（无悬空成员）"

dangle=0
while read -r k; do
  [ -z "$k" ] && continue
  [ "$(redis EXISTS "$k" 2>/dev/null | tr -d '\r')" = "1" ] || { dangle=$((dangle + 1)); echo "    悬空成员：$k"; }
done <<<"$(redis ZRANGE "$IDX" 0 -1 2>/dev/null | tr -d '\r')"
[ "$dangle" -eq 0 ] && ok "文档索引内 ${idxCard} 个成员全部指向存在的 key" \
  || bad "文档索引内有 $dangle 个悬空成员（指向已删除/已过期 key）"

# 与真实 keyspace 交叉核对（此处的 SCAN 发生在增量断言之后，不影响上面的标准）
# 注意 glob 是跨段匹配：iris:{ns}:llmcache:* 会把两个索引键与向量键一并捞出，
# 必须显式剔除 :llmcache:vec: 以及恰好以 :llmcache:idx / :llmcache:vecidx 结尾的键。
actual=$(redis --scan --pattern "iris:${NS}:llmcache:*" 2>/dev/null | tr -d '\r' \
  | grep -Ev ':llmcache:vec:|:llmcache:(idx|vecidx)$' | grep -c . )
actual=${actual:-0}
echo "  实际文档 key 数（SCAN 交叉核对）= $actual，索引基数 = ${idxCard}"
[ "$actual" -eq "${idxCard}" ] && ok "索引基数与真实 key 数一致（既不多计也不漏计）" \
  || note "索引基数(${idxCard}) 与真实 key 数($actual) 不一致——「改前未入索引的历史条目」可解释，否则查 save() 的 zadd"

vecCard=$(redis ZCARD "$VIDX" 2>/dev/null | tr -d '\r'); vecCard=${vecCard:-0}
if [ "$vecCard" -eq 0 ]; then
  note "向量索引为空（Embedder 缺席 → 只写文档），向量路径未覆盖"
else
  echo "  ZCARD $VIDX = $vecCard"
  vdangle=0
  while read -r k; do
    [ -z "$k" ] && continue
    [ "$(redis EXISTS "$k" 2>/dev/null | tr -d '\r')" = "1" ] || { vdangle=$((vdangle + 1)); echo "    悬空向量：$k"; }
  done <<<"$(redis ZRANGE "$VIDX" 0 -1 2>/dev/null | tr -d '\r')"
  [ "$vdangle" -eq 0 ] && ok "向量索引内 $vecCard 个成员全部指向存在的 key（淘汰时向量已同步清理）" \
    || bad "向量索引内有 $vdangle 个悬空成员"
  [ "$vecCard" -eq "${idxCard}" ] && ok "向量数与文档数对齐（$vecCard）" \
    || note "向量数($vecCard) 与文档数(${idxCard}) 不等——「只写文档」的旧条目可解释"
fi

# --------------------------------------------------------- 6. 收尾
hdr "6. 收尾"
curlq -X DELETE -H "X-API-Key: $API_KEY" "$BASE/api/v1/llm-cache?namespace=$NS" >/dev/null
echo "  已清空探针命名空间 $NS"

echo
echo "汇总：PASS=$pass FAIL=$fail NOTE=$warn"
if [ "$fail" -eq 0 ]; then
  echo "验收通过：LLM 缓存维护路径已索引化，请求路径零 SCAN。"
  exit 0
fi
echo "验收未通过，请核对上面的 FAIL 项。"
exit 1

#!/usr/bin/env bash
# 日志表退役清理（CDC 排除 10 张高频日志/流水表的配套）：
# 清掉这些表已入库的残留数据——投影 JSON / FT 索引 / 实体缓存索引 / 版本计数器 / stream(含DLQ)。
# 排除名单 = cdc-excluded-tables.txt（与 debezium exclude.list、application.yml sources 三处一致）。
#
# 为什么必须清：排除后这些表不再更新，Redis 里留的是全量导入期的冻结快照，
# Agent 查询会返回「看似真实、永不更新」的旧数据，误导业务标准。
#
# 【前置条件（不满足会被守卫拦下）】
#   1. iris-debezium 容器已停止（否则新事件会把 stream/投影键重新写回来）；
#   2. iris4j 应用建议已停（避免清完后消费者对已删流反复报错；PG/MySQL 无所谓）。
#
# 用法：
#   ./deploy/verify/decommission-log-tables.sh            # dry-run：只统计，不删任何东西
#   IRIS_CONFIRM=yes ./deploy/verify/decommission-log-tables.sh   # 真删（UNLINK）
#
# 【为什么这里可以用 SCAN】投影键按主键散列、无法枚举主键，只能 SCAN 前缀——
# 本脚本是离线运维路径（与 clear() 同性质），且 Debezium 已停、无并发写入，
# 每表一次前缀扫描的代价（366 万键约 5-7 秒 ×10）可接受。请求路径仍然严禁 SCAN。
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NS="${IRIS_NS:-ecomm}"
RC="${IRIS_REDIS_CONTAINER:-iris-redis}"
TOPIC_PREFIX="${IRIS_TOPIC_PREFIX:-iris.iris_demo}"
EXCL="${IRIS_EXCL:-$HERE/cdc-excluded-tables.txt}"
CONFIRM="${IRIS_CONFIRM:-no}"

[ -f "$EXCL" ] || { echo "排除名单不存在: $EXCL" >&2; exit 1; }
TABLES=$(grep -vE '^\s*#|^\s*$' "$EXCL")
N=$(printf '%s\n' "$TABLES" | grep -c .)
echo "排除表 ${N} 张，namespace=${NS} ，确认模式=${CONFIRM}"

# ---- 守卫 1：Debezium 必须已停 ----
if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx 'iris-debezium'; then
  echo "!! iris-debezium 仍在运行——新事件会把删掉的数据重新写回。" >&2
  echo "   先执行: docker stop iris-debezium" >&2
  exit 2
fi

# ---- 守卫 2：Redis 可达 ----
docker exec "$RC" redis-cli PING >/dev/null 2>&1 || { echo "!! Redis 容器 $RC 不可达" >&2; exit 2; }

rc() { docker exec "$RC" redis-cli "$@" 2>/dev/null; }

total_keys=0
for t in $TABLES; do
  index="iris:${NS}:index:${t}"
  ver="iris:${NS}:ver:${t}"
  cacheidx="iris:${NS}:cacheidx:${t}"
  stream="${TOPIC_PREFIX}.${t}"
  dlq="${stream}:dlq"
  pat="iris:${NS}:entity:${t}:*"

  if [ "$CONFIRM" != "yes" ]; then
    nk=$(rc --scan --pattern "$pat" | grep -c . || true)
    # 注意：FT 索引对 EXISTS/--scan 均不可见（keyspace 层面看不到），必须用 FT.INFO 判存在
    idx=$(rc FT.INFO "$index" 2>/dev/null | grep -c num_docs)
    echo "[dry] $t  投影键=$nk  索引存在=$idx  stream=$(rc XLEN "$stream")  dlq=$(rc XLEN "$dlq")  cacheidx=$(rc SCARD "$cacheidx")  ver=$(rc EXISTS "$ver")"
    continue
  fi

  # 1. 投影键：--scan 批量 UNLINK（500/批）
  rc --scan --pattern "$pat" | xargs -n 500 -r sh -c 'docker exec '"$RC"' redis-cli UNLINK "$@" >/dev/null' sh 2>/dev/null
  nk=$(rc --scan --pattern "$pat" | grep -c . || true)   # 复扫：非 0 即有残留，删除不净

  # 2. 实体缓存条目：SMEMBERS 出相对 key 段，逐个 DEL，再删索引本身
  cdel=0
  while IFS= read -r m; do
    [ -n "$m" ] || continue
    rc DEL "iris:${NS}:cache:${m}" >/dev/null
    cdel=$((cdel + 1))
  done < <(rc SMEMBERS "$cacheidx")
  rc DEL "$cacheidx" >/dev/null

  # 3. FT 索引 / 版本计数器 / stream（含消费组）/ DLQ
  idx=$(rc FT.DROPINDEX "$index" 2>&1)
  rc DEL "$ver" "$stream" "$dlq" >/dev/null

  total_keys=$((total_keys + nk))
  printf "[done] %-28s 投影键复扫残留=%s 缓存清理=%s 索引DROP=%s\n" "$t" "$nk" "$cdel" "$idx"
done

echo "----"
if [ "$CONFIRM" = "yes" ]; then
  echo "完成：共删除投影键 $total_keys 条。"
  echo "下一步：docker start iris-debezium（新 exclude.list 生效）→ 重建并启动 iris4j（sources 已缩减 125 块）"
else
  echo "dry-run 结束，未删除任何数据。确认无误后执行：IRIS_CONFIRM=yes $0"
fi

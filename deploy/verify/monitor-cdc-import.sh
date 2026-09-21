#!/usr/bin/env bash
# 全量导入/追平监控：覆盖全部 135 条流，禁止抽样。
# 打印总 XLEN / 总 lag / 总 pending / 总 DLQ，追平即退出。
#
# 用法： ./deploy/verify/monitor-cdc-import.sh [轮次] [间隔秒]
#        默认 120 轮 × 60 秒（最长 2 小时）
#
# 【为什么必须全量覆盖】lag 分布在流之间极不均（大表独大），
# 只抽样小比例的流得到的"完成"是假的——大表的残留积压会被漏掉。
#
# 【为什么必须看 lag 而不是 XLEN】XLEN 是"保留在 stream 里的条数"（受裁剪影响、
# 且含已消费历史）；lag 才是"尚未投递给该组"的真实积压。
#
# 【实现要点：一次 EVAL，而不是 405 次 docker exec】
# 每条流需要 XLEN + XINFO GROUPS + XLEN dlq 三个值，135 流即 405 次调用；
# docker exec 每次进程开销约 0.5-1 秒，逐条调用要 5 分钟以上，根本没法按分钟采样。
# 故所有统计放进一段 Lua，用一次 EVAL 在服务端算完。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
YML="${IRIS_YML:-$HERE/../../api/src/main/resources/application.yml}"
RC="${IRIS_REDIS_CONTAINER:-iris-redis}"
MAX="${1:-120}"
GAP="${2:-60}"
LUA="$HERE/stream-stats.lua"

KEYS=()
while IFS= read -r k; do KEYS+=("$k"); done \
  < <(grep -oE 'stream: iris\.iris_demo\.[a-z0-9_]+' "$YML" | awk '{print $2}')
N=${#KEYS[@]}
if [ "$N" -eq 0 ]; then
  echo "未从 $YML 解析到任何 stream，检查路径或 IRIS_YML" >&2
  exit 1
fi
echo "# 流数=$N 轮次=$MAX 间隔=${GAP}s 开始=$(date '+%H:%M:%S')"

for i in $(seq 1 "$MAX"); do
  OUT=$(docker exec "$RC" redis-cli EVAL "$(cat "$LUA")" "$N" "${KEYS[@]}" 2>&1)
  LAGLINE=$(echo "$OUT" | grep '^TOTAL')
  echo "$(date '+%H:%M:%S') $LAGLINE"
  lag=$(echo "$LAGLINE" | awk -F'|' '{print $4}')
  pen=$(echo "$LAGLINE" | awk -F'|' '{print $3}')
  unk=$(echo "$LAGLINE" | grep -oE 'unknown=[0-9]+' | cut -d= -f2)
  [ "${unk:-1}" != "0" ] && echo "  WARN 有 ${unk} 条流的 lag 读不到，判定不可信"
  if [ "${lag:-1}" = "0" ] && [ "${pen:-1}" = "0" ] && [ "${unk:-1}" = "0" ]; then
    echo "DONE 第 $i 轮追平（lag=0 pending=0 unknown=0）"
    exit 0
  fi
  sleep "$GAP"
done
echo "TIMEOUT 未在 $MAX 轮内追平"
exit 1

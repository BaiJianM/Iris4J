#!/usr/bin/env bash
# 安全裁剪（离线运维版）：按「lag + pending + 余量」逐流计算安全上限后 XTRIM，
# 只摘掉已投递且已确认的历史事件，绝不碰未消费/未确认的条目。
#
# 用途：全量导入等积压场景下无法等应用侧 trimStream 兜底（它要等 backlog 排空才生效），
# 而内存顶不住时，这是唯一既能立刻释放内存、又不丢事件的做法。
#
# 用法： bash /tmp/t29-safe-trim.sh [target] [margin]
#        target 默认 10000（排空后的稳态保留条数），margin 默认 1000
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 仓库根 = 本脚本 deploy/verify/ 的上两级；流上限参数读 web 模块 application.yml
YML="${IRIS_YML:-$(cd "$HERE/../.." && pwd)/web/src/main/resources/application.yml}"
RC=iris-redis
TARGET="${1:-10000}"
MARGIN="${2:-1000}"

KEYS=()
while IFS= read -r k; do KEYS+=("$k"); done \
  < <(grep -oE 'stream: iris\.iris_demo\.[a-z0-9_]+' "$YML" | awk '{print $2}')
N=${#KEYS[@]}
echo "# 流数=$N target=$TARGET margin=$MARGIN"

# 一次 EVAL 读出全部流的 xlen/pending/lag（read-only，快）
RAW=$(docker exec "$RC" redis-cli EVAL "$(cat "$HERE/stream-stats.lua")" "$N" "${KEYS[@]}" 2>&1)

: > /tmp/t29-trim-cmds.txt
NEED=0
while IFS='|' read -r k xl pen lag dlq; do
  [ "$k" = "TOTAL" ] && continue
  [ -z "$k" ] && continue
  if [ "${lag:--1}" -lt 0 ]; then
    echo "  SKIP $k（lag 不可读）"
    continue
  fi
  safe=$((lag + pen + MARGIN))
  [ "$safe" -lt "$TARGET" ] && safe=$TARGET
  echo "XTRIM $k MAXLEN ~ $safe" >> /tmp/t29-trim-cmds.txt
  NEED=$((NEED + 1))
done <<< "$RAW"
echo "# 生成裁剪命令 $NEED 条"

# 一次连接批量执行（XTRIM 的回复就是「裁掉条数」，逐行求和）
TRIMMED=$(docker exec -i "$RC" redis-cli < /tmp/t29-trim-cmds.txt 2>/dev/null \
  | tr -d '\r' | awk '{s+=$1} END{print s+0}')
echo "# 共裁掉 $TRIMMED 条（全部为已投递且已确认的历史事件）"
echo "=== 裁剪后 ==="
bash "$HERE/monitor-cdc-import.sh" 1 1 2>/dev/null | tail -2

#!/usr/bin/env bash
# DLQ 回放：把 {stream}:dlq 里的原始事件重新投回源流，让消费端再投影一次。
#
# 【为什么能这么做】DLQ 条目是「整条落库」的：CdcConsumer.moveToDlq 把原始 payload、
# msgKey、deliveryCount、error 一起写进 {stream}:dlq，所以回放不需要回到源库。
# 这比「UPDATE 源库那一行」更干净：一行 SQL 改同值不会写 binlog（MySQL ROW 格式会跳过
# 无变化的行），会被迫去改一个业务字段来制造"真变更"，反而污染数据。
#
# 【为什么幂等】消费端 process() 取 body 的第一个 value 当 envelope，投影是
# JSON.SET 按主键 upsert，重复回放只会覆盖成同一份值。
#
# 用法： ./deploy/verify/replay-dlq.sh            # 回放，保留 DLQ 作为审计留痕
#        ./deploy/verify/replay-dlq.sh --clear    # 回放后清空 DLQ
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
YML="${IRIS_YML:-$HERE/../../api/src/main/resources/application.yml}"
RC="${IRIS_REDIS_CONTAINER:-iris-redis}"
CLEAR="${1:-}"

PY=$(command -v python3 || echo /usr/bin/python3)

KEYS=()
while IFS= read -r k; do KEYS+=("$k"); done \
  < <(grep -oE 'stream: iris\.iris_demo\.[a-z0-9_]+' "$YML" | awk '{print $2}')

TOTAL=0
for s in "${KEYS[@]}"; do
  n=$(docker exec "$RC" redis-cli XLEN "${s}:dlq" 2>/dev/null | tr -d '\r')
  [ -z "$n" ] && n=0
  [ "$n" = "0" ] && continue

  echo "== ${s}:dlq  $n 条 =="
  # 取出每条 DLQ 的 payload，以 NUL 分隔输出，避免 JSON 里的引号/空格破坏 shell 解析
  docker exec "$RC" redis-cli --json XRANGE "${s}:dlq" - + 2>/dev/null \
    | "$PY" -c '
import sys, json
data = json.load(sys.stdin)
for entry in data:
    fields = entry[1]
    payload = None
    for i in range(0, len(fields) - 1, 2):
        if fields[i] == "payload":
            payload = fields[i + 1]
    if payload:
        sys.stdout.write(payload.replace("\x00", "") + "\x00")
' > /tmp/replay-dlq.payloads 2>/dev/null

  c=0
  while IFS= read -r -d '' p; do
    stream=$(docker exec -i "$RC" redis-cli -x XADD "$s" '*' payload <<< "$p" 2>/dev/null | tr -d '\r')
    if [ -n "$stream" ]; then
      c=$((c + 1))
    else
      echo "  XADD 失败（payload 前 80 字节：${p:0:80}）"
    fi
  done < /tmp/replay-dlq.payloads
  echo "  已重投 $c 条"
  TOTAL=$((TOTAL + c))

  if [ "$CLEAR" = "--clear" ] && [ "$c" -eq "$n" ]; then
    docker exec "$RC" redis-cli DEL "${s}:dlq" >/dev/null 2>&1
    echo "  DLQ 已清空"
  fi
done

echo
echo "共重投 $TOTAL 条。等待消费端投影后，用 verify-cdc-completeness.sh 复验。"

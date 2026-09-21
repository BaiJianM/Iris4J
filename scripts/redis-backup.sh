#!/usr/bin/env bash
# Redis 数据备份（方案 L281「高可用、备份、恢复和故障演练」、6.2 持久化）
#
# 做法：BGSAVE 产生新快照后，把整个 /data 目录（dump.rdb + appendonlydir/，
# 含 AOF 与消费组位点）打成 tar 存到 deploy/backups/，保留最近 KEEP 份。
#
# 说明：
# - 不停机备份：dump.rdb 在 BGSAVE 完成后是完整文件；AOF 增量文件为追加写，
#   tar 抓取的是某一时刻快照，Redis 加载时对 AOF 末尾不完整事务有截断容忍
#   （aof-load-truncated 默认 yes）。要求更小 RPO 时先停应用再备份。
# - 恢复：配合 scripts/redis-restore.sh 使用（停机 + 整目录还原）。
#
# 用法：./scripts/redis-backup.sh   （需已 cd 到项目根目录或脚本自动定位）

set -euo pipefail

KEEP=7
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKUP_DIR="$PROJECT_ROOT/deploy/backups"
TS="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/redis-data-$TS.tar.gz"

DOCKER_BIN="${DOCKER_BIN:-docker}"
if [ -x "/Applications/Docker.app/Contents/Resources/bin/docker" ]; then
  DOCKER_BIN="/Applications/Docker.app/Contents/Resources/bin/docker"
fi

command -v "$DOCKER_BIN" >/dev/null || { echo "错误: 找不到 docker"; exit 1; }
"$DOCKER_BIN" ps --format '{{.Names}}' | grep -qx 'iris-redis' \
  || { echo "错误: iris-redis 容器未运行"; exit 1; }

mkdir -p "$BACKUP_DIR"

echo "==> BGSAVE 生成新 RDB 快照..."
"$DOCKER_BIN" exec iris-redis redis-cli BGSAVE
# 等待 BGSAVE 完成（rdb_bgsave_in_progress:1 -> 0），最多 30s
for _ in $(seq 1 30); do
  if [ "$("$DOCKER_BIN" exec iris-redis redis-cli info persistence \
      | tr -d '\r' | grep -c 'rdb_bgsave_in_progress:1')" = "0" ]; then
    break
  fi
  sleep 1
done
"$DOCKER_BIN" exec iris-redis redis-cli info persistence | tr -d '\r' | grep 'rdb_last_bgsave_status'

echo "==> 打包 /data -> $OUT"
"$DOCKER_BIN" exec iris-redis tar czf - -C /data . > "$OUT"

echo "==> 清理旧备份（保留最近 $KEEP 份）"
ls -1t "$BACKUP_DIR"/redis-data-*.tar.gz 2>/dev/null | tail -n +$((KEEP + 1)) | while read -r f; do
  rm -f "$f" && echo "    已删除: $f"
done

SIZE="$(du -h "$OUT" | cut -f1)"
echo "==> 备份完成: $OUT ($SIZE)"

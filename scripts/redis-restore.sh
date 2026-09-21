#!/usr/bin/env bash
# Redis 数据恢复演练（方案 L281、6.2：RDB 备份和恢复演练）
#
# 做法：停 iris-redis -> 清空数据卷 -> 把备份 tar 解回 /data -> 启动。
# 因为备份包含 appendonlydir（AOF）与 dump.rdb，整目录还原后
# 投影、缓存、Stream、消费组位点全部原样回来，无需任何 AOF/RDB 切换操作。
#
# ⚠️ 演练会清空当前 redis-data 卷内容，请先执行 scripts/redis-backup.sh 生成备份。
#
# 用法: ./scripts/redis-restore.sh <backup.tar.gz>
#   例: ./scripts/redis-restore.sh deploy/backups/redis-data-20260903-190000.tar.gz

set -euo pipefail

BACKUP="$1"
DOCKER_BIN="${DOCKER_BIN:-docker}"
if [ -x "/Applications/Docker.app/Contents/Resources/bin/docker" ]; then
  DOCKER_BIN="/Applications/Docker.app/Contents/Resources/bin/docker"
fi

[ -f "$BACKUP" ] || { echo "错误: 备份文件不存在: $BACKUP"; exit 1; }
command -v "$DOCKER_BIN" >/dev/null || { echo "错误: 找不到 docker"; exit 1; }
"$DOCKER_BIN" volume inspect iris-lite_redis-data >/dev/null 2>&1 \
  || { echo "错误: 卷 iris-lite_redis-data 不存在"; exit 1; }

echo "==> 停止 iris-redis"
"$DOCKER_BIN" stop iris-redis >/dev/null

echo "==> 清空数据卷并还原备份: $BACKUP"
# 用临时 redis 容器挂卷操作（卷被占用时容器必须停着；用本地必有的 redis 镜像，
# 避免 alpine 不在本地时拉取失败）
"$DOCKER_BIN" run --rm -v iris-lite_redis-data:/data -v "$(cd "$(dirname "$BACKUP")" && pwd)":/backup:ro \
  redis:8.10.1 sh -c "rm -rf /data/* /data/..?* /data/.[!.]* 2>/dev/null; tar xzf /backup/$(basename "$BACKUP") -C /data && ls -la /data"

echo "==> 启动 iris-redis"
"$DOCKER_BIN" start iris-redis >/dev/null

# 等待就绪（compose healthcheck 5s 间隔，这里直接 ping）
echo "==> 等待 Redis 就绪..."
for _ in $(seq 1 30); do
  if "$DOCKER_BIN" exec iris-redis redis-cli ping 2>/dev/null | grep -q PONG; then
    break
  fi
  sleep 1
done
"$DOCKER_BIN" exec iris-redis redis-cli ping

DBSIZE="$("$DOCKER_BIN" exec iris-redis redis-cli dbsize)"
echo "==> 恢复完成，dbsize=$DBSIZE"
echo "    建议核对: 投影 key（iris:*:entity:*）、Stream 长度、消费组位点（XINFO GROUPS）"

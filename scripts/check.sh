#!/usr/bin/env bash
# iris-lite 连通性检查脚本（方案 7.2 允许的简单检查）。
# 只做：配置解析、服务状态、Redis 连接、MySQL 连接、iris-lite 健康检查。
set -euo pipefail

echo "== 1. compose 配置解析 =="
docker compose config --quiet && echo "compose 配置 OK"

echo "== 2. 服务状态 =="
docker compose ps

echo "== 3. Redis ping =="
docker exec iris-redis redis-cli ping

echo "== 4. MySQL 连接 =="
docker exec iris-mysql mysqladmin ping -h 127.0.0.1 -piris-root

echo "== 5. iris-lite 健康检查（若本地已启动） =="
if curl -sf http://127.0.0.1:8080/actuator/health; then
  echo ""
else
  echo "iris-lite 未在本地 8080 端口运行（首期用 IDE/java -jar 本地启动，属预期）"
fi

echo "== 完成 =="

#!/usr/bin/env bash
# 停止 start-app.sh 启动的 jar 进程。
# 进程定位：按 deploy/.env 里 APP_JAR_PATH 精确匹配，避免误杀其他 java 进程。
# 停止方式：先 SIGTERM 走 Spring Boot 优雅停机
# （application.yml 配了 server.shutdown: graceful，单阶段超时 20s），
# 最多等 30s，还没退就 SIGKILL 强杀。
#
# 用法（在 server 目录下）： ./stop-app.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../deploy/.env"

set -a
source "$ENV_FILE"
set +a

JAR_PATH="${APP_JAR_PATH:?请在 deploy/.env 里设置 APP_JAR_PATH=/path/to/app.jar}"

PIDS="$(pgrep -f "java.*${JAR_PATH}" || true)"
if [ -z "$PIDS" ]; then
  echo "没有找到正在运行的进程：$JAR_PATH"
  exit 0
fi

echo "发送停止信号: $PIDS ($JAR_PATH)"
kill $PIDS

TIMEOUT=30
for _ in $(seq 1 "$TIMEOUT"); do
  if ! pgrep -f "java.*${JAR_PATH}" >/dev/null; then
    echo "已优雅退出"
    exit 0
  fi
  sleep 1
done

echo "${TIMEOUT}s 内未退出，强制结束"
pkill -KILL -f "java.*${JAR_PATH}"

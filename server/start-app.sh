#!/usr/bin/env bash
# 用 deploy/.env 里的账密启动后台 jar。
# 账密不进 jar、不进 yml，只存在 deploy/.env（已在 .gitignore 里）。
# 本脚本放在 server/，与 deploy/ 平级：配置留在 deploy（compose 要读同目录 .env），
# 运行产物（日志）落在本目录下的 logs/ —— 即 server/logs/，该目录已在 .gitignore 里。
#
# 用法（在 server 目录下）：
#   ./start-app.sh              前台运行，日志直接打在终端，Ctrl+C 结束（调试用）
#   ./start-app.sh -d           后台运行，关掉终端/断开 SSH 也不会退出
#   ./start-app.sh --daemon     同上
#   ./stop-app.sh               停止（前台/后台启动的都能停）
#
# 后台模式不重定向到日志文件：应用日志由 logback 自己写
# （logback-spring.xml 的 FILE appender 用相对路径 ${APP_LOG_DIR:-./logs}，
#   脚本会 cd 到本目录，所以落盘为 server/logs/meal-order.log），
# 控制台输出在这里直接丢弃。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../deploy/.env"

# 0) 解析参数：-d / --daemon 表示后台运行。
DAEMON=0
case "${1:-}" in
  -d|--daemon) DAEMON=1 ;;
  "") ;;
  *)
    echo "用法: $0 [-d|--daemon]" >&2
    exit 2
    ;;
esac

# 1) 加载 .env（在 deploy/ 下）：set -a 让其中所有 KEY=VALUE 自动 export 成环境变量。
#    APP_JWT_SECRET / APP_WX_APP_ID / APP_WX_APP_SECRET / APP_ADMIN_*
#    与 yml 里的占位符同名，加载后直接生效，无需映射。
set -a
source "$ENV_FILE"
set +a

# 2) compose 用的是 MYSQL_*，jar 的 yml 要 APP_DB_*，这里做映射。
#    mysql 已映射到宿主机 127.0.0.1:3306，所以 URL 用回环地址。
#    库名直接取 .env 的 MYSQL_DATABASE，不写死。
export SPRING_PROFILES_ACTIVE=prod
export APP_DB_USER="$MYSQL_USER"
export APP_DB_PASSWORD="$MYSQL_PASSWORD"
export APP_DB_URL="jdbc:mysql://127.0.0.1:3306/${MYSQL_DATABASE}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"

# 3) 启动。jar 路径从 .env 的 APP_JAR_PATH 读取（没设置就直接报错退出）；
#    JVM 参数从 JAVA_OPTS 读取，多个参数用空格分隔
#    （如 JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"），没写就按 JVM 默认参数跑。
#    注意整个值要加引号：.env 是被 source 的，不加引号时第二个参数会被当成命令。
JAR_PATH="${APP_JAR_PATH:?请在 deploy/.env 里设置 APP_JAR_PATH=/path/to/app.jar}"
cd "$SCRIPT_DIR"

if [ "$DAEMON" = "1" ]; then
  # 后台模式：nohup 让进程忽略 SIGHUP，关终端不会被带走；
  # 控制台输出丢弃，应用日志看 logs/meal-order.log。
  nohup java ${JAVA_OPTS:-} -jar "$JAR_PATH" >/dev/null 2>&1 &
  echo "已后台启动: PID=$! jar=$JAR_PATH"
  echo "查看日志: tail -f $SCRIPT_DIR/logs/meal-order.log"
  echo "停止服务: $SCRIPT_DIR/stop-app.sh"
else
  exec java ${JAVA_OPTS:-} -jar "$JAR_PATH"
fi

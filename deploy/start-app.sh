#!/usr/bin/env bash
# 用 deploy/.env 里的账密启动后台 jar。
# 账密不进 jar、不进 yml，只存在 .env（已在 .gitignore 里）。
#
# 用法： ./start-app.sh /path/to/app.jar
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 1) 加载 .env：set -a 让其中所有 KEY=VALUE 自动 export 成环境变量。
#    APP_JWT_SECRET / APP_WX_APP_ID / APP_WX_APP_SECRET / APP_ADMIN_*
#    与 yml 里的占位符同名，加载后直接生效，无需映射。
set -a
source "$SCRIPT_DIR/.env"
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
exec java ${JAVA_OPTS:-} -jar "$JAR_PATH"

# =============================================================================
# 服务端镜像（多阶段构建）
# 若改用 JDK 25：把两个 FROM 的 17 都换成 25 即可
#
# 架构：本文件不含任何 --platform 声明。基础镜像都是多架构的，
#      构建出什么架构取决于「在哪台机器上 build」——在 amd64 服务器上 build 就是 amd64。
#      因此不要「Windows 上 build 完把镜像拷到服务器」，直接在服务器上 build 即可。
# =============================================================================

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Maven 走阿里云镜像，否则境内服务器首次构建要跟 Maven Central 死磕几分钟。
# 两处 mvn 都用 -s 显式指定：不依赖镜像的家目录布局（万一基础镜像换成非 root 用户，
# 丢进 /root/.m2 会静默失效），也让「用了哪份 settings」在文件里一眼可见。
COPY maven-settings.xml /build/maven-settings.xml

# 2 核 4G 机器：Maven 默认允许堆涨得很大，与 MySQL 的 512M buffer pool 叠加会 OOM，
# 报错还会伪装成「编译进程被 kill」。这里给个明确上限。
ENV MAVEN_OPTS="-Xmx768m"

# 先只拷 pom，利用镜像层缓存依赖下载（pom 不改就不会重新下载）
COPY pom.xml ./
RUN mvn -s /build/maven-settings.xml -B -q -Dmaven.test.skip=true dependency:go-offline || true

COPY src ./src
# 用 maven.test.skip 而不是 skipTests：skipTests 只是不执行测试，
# 仍会编译 src/test 下 6 个测试类，在 2 核机器上纯属白烧时间与内存。
RUN mvn -s /build/maven-settings.xml -B -q -Dmaven.test.skip=true clean package

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre

WORKDIR /app
# SPRING_PROFILES_ACTIVE=prod 写在镜像里：镜像只有生产一份用途，
# 漏传环境变量也不会落回 application.yml 的 dev 默认值（连 127.0.0.1、打全量 SQL）。
ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    SPRING_PROFILES_ACTIVE=prod \
    APP_LOG_DIR=/app/logs \
    APP_UPLOAD_DIR=/data/upload

# curl 仅用于容器健康检查与排障
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/logs /data/upload

COPY --from=build /build/target/app.jar /app/app.jar

EXPOSE 8080

# 2 核 4G 机器：堆固定 1G，与 MySQL 的 512M buffer pool 共存不抢内存
# SerialGC 在小堆、低 QPS 场景下比 G1 更省内存与 CPU
ENTRYPOINT ["java", \
    "-Xms512m", "-Xmx1g", "-Xss512k", \
    "-XX:+UseSerialGC", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Duser.timezone=Asia/Shanghai", \
    "-jar", "/app/app.jar"]

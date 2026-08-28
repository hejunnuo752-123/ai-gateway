# =============================================================================
# AI Gateway — 容器镜像
#
# 关键设计说明（源于项目代码的实际约束）：
#   1. 代码里 data-dir 默认值为相对路径 "data"，logback 和 LogController 也写死了
#      "logs/xxx.log"，两者都相对于进程工作目录解析。所以 WORKDIR 必须固定为
#      /app，并把 /app/data、/app/logs 挂成卷，否则容器重建后配置与日志全丢。
#   2. data/backgrounds/ 由 StaticResourceConfig 映射到 /bg/**，且需要运行时可写
#      （用户上传背景图），必须落在卷里而不能打进 jar。
#   3. 项目不依赖 java.awt / ImageIO（已全量检索确认），可安全使用 headless
#      alpine JRE，无需 fontconfig 等原生库。
#   4. pom.xml 未引入 spring-boot-starter-actuator，因此健康检查不能打
#      /actuator/health，改用鉴权白名单里的 /login（未登录也返回 200）。
# =============================================================================

# ---------- 阶段 1：构建 ----------
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 先只拷 pom，让依赖层能被缓存：改代码不会重新下载依赖。
# go-offline 对 Spring Boot 工程偶有插件解析不全的情况，失败不阻断构建，
# 后续 package 阶段会自动补拉缺失依赖。
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
# spring-boot-maven-plugin 会同时产出 ai-gateway-1.0.0.jar 与 .jar.original，
# 通配符只会命中前者（可执行 fat jar）
RUN mvn -B clean package -DskipTests \
    && mv target/ai-gateway-*.jar target/app.jar


# ---------- 阶段 2：运行 ----------
FROM eclipse-temurin:17-jre-alpine

# tzdata：alpine 默认不含时区库，缺了 TZ 环境变量不生效，日志时间会是 UTC
# curl：给 HEALTHCHECK 用
RUN apk add --no-cache tzdata curl \
    && addgroup -S -g 1000 appuser \
    && adduser -S -u 1000 -G appuser appuser

WORKDIR /app

COPY --from=builder /build/target/app.jar ./app.jar

# 预建数据目录并交给非 root 用户，避免挂载空卷后因权限写不进去
RUN mkdir -p /app/data/backgrounds /app/logs \
    && chown -R appuser:appuser /app

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    SERVER_PORT=8822 \
    AI_GATEWAY_DATA_DIR=/app/data \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport -Dfile.encoding=UTF-8"

# 运行时数据与日志必须持久化，否则重建容器会丢失供应商/资源组/密钥配置
VOLUME ["/app/data", "/app/logs"]

EXPOSE 8822

USER appuser

# /login 在 AdminAuthFilter 白名单内，未登录也返回 200，适合做存活探测
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/login" > /dev/null || exit 1

# 用 sh -c 以便展开变量；exec 保证 java 成为 PID 1，能正确收到 SIGTERM。
# data-dir / port 用显式命令行参数传入而不依赖环境变量松散绑定，
# 避免 AI_GATEWAY_DATA_DIR 因连字符属性名映射差异而失效。
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --server.port=${SERVER_PORT} --ai-gateway.data-dir=${AI_GATEWAY_DATA_DIR}"]

# WavePilot 通信仿真实验智能平台 - Docker 镜像
# 多阶段构建：Maven 编译 → JRE 运行（产物只保留可执行 jar）

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# 先拷贝 pom 单独解析依赖，利用 Docker 层缓存减少重复下载
COPY pom.xml .
RUN mvn -B dependency:go-offline -q || true
COPY src ./src
RUN mvn -B clean package -DskipTests -q

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
# 非 root 运行更安全（数据目录由 compose 挂载，需保证可写）
RUN useradd -m -u 10001 wavepilot
COPY --from=build /build/target/*.jar app.jar
RUN mkdir -p /app/artifacts /app/data/wavepilot/templates /app/uploads \
    && chown -R wavepilot:wavepilot /app
USER wavepilot

ENV JAVA_OPTS="-Xms256m -Xmx1g"
# 默认 Mock Runner；本地 MATLAB 无法进入容器（授权/体积），真实仿真需在宿主机跑
# Embedding 默认真实（DashScope，维度与 Milvus collection 一致）；无 key 时自主模式回退 Stub
ENV WAVEPILOT_RUNNER_TYPE=mock \
    WAVEPILOT_KNOWLEDGE_REPOSITORY=milvus \
    WAVEPILOT_EMBEDDING_OFFLINE=false \
    DASHSCOPE_API_KEY=not-configured \
    MILVUS_HOST=milvus

EXPOSE 9900
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

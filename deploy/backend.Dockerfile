# syntax=docker/dockerfile:1.7

FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace

# Keep dependency resolution in a layer that changes only when Maven metadata
# changes. The cache mount is reused by the local BuildKit builder.
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY framework/pom.xml framework/pom.xml
COPY infra-ai/pom.xml infra-ai/pom.xml
COPY bootstrap/pom.xml bootstrap/pom.xml
COPY mcp-server/pom.xml mcp-server/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw \
    && ./mvnw -B -ntp -Dmaven.test.skip=true dependency:go-offline

COPY . .

# The existing application.yaml contains local-only values.  Do not publish a
# final image built from those credentials: replace secret values with runtime
# placeholders before Spring Boot packages the application resources.
RUN --mount=type=cache,target=/root/.m2 \
    sed -i '/^  datasource:/,/^  data:/ s|^    password:.*|    password: ${RAGENT_DB_PASSWORD}|' bootstrap/src/main/resources/application.yaml \
    && sed -i '/^  data:/,/^rocketmq:/ s|^      password:.*|      password: ${RAGENT_REDIS_PASSWORD}|' bootstrap/src/main/resources/application.yaml \
    && sed -i '/^    bailian:/,/^    aihubmix:/ s|^      api-key:.*|      api-key: ${RAGENT_BAILIAN_API_KEY}|' bootstrap/src/main/resources/application.yaml \
    && sed -i '/^    aihubmix:/,/^    siliconflow:/ s|^      api-key:.*|      api-key: ${RAGENT_AIHUBMIX_API_KEY}|' bootstrap/src/main/resources/application.yaml \
    && sed -i '/^    siliconflow:/,/^  selection:/ s|^      api-key:.*|      api-key: ${RAGENT_SILICONFLOW_API_KEY}|' bootstrap/src/main/resources/application.yaml \
    && sed -i '/^rustfs:/,/^# MinerU/ s|^  secret-access-key:.*|  secret-access-key: ${RAGENT_RUSTFS_SECRET_KEY}|' bootstrap/src/main/resources/application.yaml \
    && sed -i '/^mineru:/,/^sa-token:/ s|^  api-key:.*|  api-key: ${RAGENT_MINERU_API_KEY}|' bootstrap/src/main/resources/application.yaml \
    && chmod +x mvnw \
    && ./mvnw -B -ntp -Dmaven.test.skip=true -pl bootstrap -am package

FROM eclipse-temurin:17-jre-jammy AS runtime

RUN groupadd --system ragent && useradd --system --gid ragent --home-dir /app ragent
WORKDIR /app

COPY --from=build /workspace/bootstrap/target/bootstrap-*.jar /app/app.jar

USER ragent
EXPOSE 9090

ENTRYPOINT ["java", "-Xms256m", "-Xmx1536m", "-XX:MaxMetaspaceSize=256m", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar"]

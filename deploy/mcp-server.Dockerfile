# syntax=docker/dockerfile:1.7

FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace

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
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw \
    && ./mvnw -B -ntp -Dmaven.test.skip=true -pl mcp-server -am package \
    && java -Djarmode=tools -jar mcp-server/target/mcp-server-*.jar \
        extract --layers --launcher --destination mcp-server/target/extracted

FROM eclipse-temurin:17-jre-jammy AS runtime

RUN groupadd --system ragent && useradd --system --gid ragent --home-dir /app ragent
WORKDIR /app

# One COPY per Spring Boot layer. Pure code changes leave the dependency layers
# byte-identical, so their digests stay the same and TCR skips re-uploading them.
COPY --from=build /workspace/mcp-server/target/extracted/dependencies/ ./
COPY --from=build /workspace/mcp-server/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/mcp-server/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/mcp-server/target/extracted/application/ ./

USER ragent
EXPOSE 9099

ENTRYPOINT ["java", "-Xms128m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "org.springframework.boot.loader.launch.JarLauncher"]

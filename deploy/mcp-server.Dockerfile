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
    && ./mvnw -B -ntp -Dmaven.test.skip=true -pl mcp-server -am package

FROM eclipse-temurin:17-jre-jammy AS runtime

RUN groupadd --system ragent && useradd --system --gid ragent --home-dir /app ragent
WORKDIR /app
COPY --from=build /workspace/mcp-server/target/mcp-server-*.jar /app/app.jar

USER ragent
EXPOSE 9099

ENTRYPOINT ["java", "-Xms128m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-jar", "/app/app.jar"]

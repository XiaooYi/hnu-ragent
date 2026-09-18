# syntax=docker/dockerfile:1

FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY . .
RUN chmod +x mvnw \
    && ./mvnw -B -ntp -pl mcp-server -am clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy AS runtime

RUN groupadd --system ragent && useradd --system --gid ragent --home-dir /app ragent
WORKDIR /app
COPY --from=build /workspace/mcp-server/target/mcp-server-*.jar /app/app.jar

USER ragent
EXPOSE 9099

ENTRYPOINT ["java", "-Xms128m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-jar", "/app/app.jar"]

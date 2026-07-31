# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-alpine@sha256:1ff763083f2993d57d0bf374ab10bb3e2cb873af6c13a04458ebbd3e0337dc76 AS builder

WORKDIR /workspace

COPY gradle ./gradle
COPY gradlew build.gradle settings.gradle lombok.config ./
RUN chmod +x gradlew

COPY src/main ./src/main
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon \
    && app_jar="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" \
    && test -n "$app_jar" \
    && cp "$app_jar" /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c

RUN addgroup -S -g 10001 albam \
    && adduser -S -D -H -u 10001 -G albam albam \
    && mkdir -p /app \
    && chown 10001:10001 /app

WORKDIR /app

COPY --from=builder --chown=10001:10001 /workspace/app.jar /app/app.jar
COPY --chown=10001:10001 --chmod=0555 docker/backend-entrypoint.sh /app/backend-entrypoint.sh

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC -Dfile.encoding=UTF-8"

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["/app/backend-entrypoint.sh"]
CMD ["java", "-jar", "/app/app.jar"]

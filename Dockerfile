# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /workspace

COPY gradle ./gradle
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew

COPY src/main ./src/main
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder --chown=10001:10001 /workspace/build/libs/*.jar ./app.jar

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]

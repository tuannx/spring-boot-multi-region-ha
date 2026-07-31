FROM gradle:8-jdk17 AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src src/
RUN gradle clean bootJar --no-daemon --stacktrace

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system spring \
    && useradd --system --gid spring spring \
    && apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=builder /app/build/libs/*.jar app.jar
RUN chown spring:spring app.jar
USER spring
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=6 \
  CMD curl --fail --silent http://localhost:${SERVER_PORT:-8080}/health >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]

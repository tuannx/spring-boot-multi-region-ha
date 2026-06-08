# Simple Dockerfile - build with gradle image
FROM gradle:8-jdk17 AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src src/
RUN gradle clean build --no-daemon -x test --stacktrace 2>&1

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd -r spring && useradd -r -g spring spring
COPY --from=builder /app/build/libs/*.jar app.jar
RUN chown -R spring:spring /app
USER spring
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:${APP_PORT:-8080}/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar", "-Djava.security.egd=file:/dev/./urandom", "-Dserver.port=${APP_PORT:-8080}"]

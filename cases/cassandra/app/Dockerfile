FROM gradle:9.6.1-jdk26 AS gradle-distribution

FROM amazoncorretto:26.0.2-alpine AS builder
COPY --from=gradle-distribution /opt/gradle /opt/gradle
ENV PATH="/opt/gradle/bin:${PATH}"
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src src/
RUN gradle clean bootJar --no-daemon --stacktrace

FROM amazoncorretto:26.0.2-alpine
WORKDIR /app
RUN addgroup -S spring \
    && adduser -S -G spring spring \
    && apk add --no-cache curl
COPY --from=builder /app/build/libs/*.jar app.jar
RUN chown spring:spring app.jar
USER spring
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=6 \
  CMD curl --fail --silent http://localhost:${SERVER_PORT:-8080}/health >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]

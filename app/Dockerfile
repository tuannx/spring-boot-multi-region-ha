# Build with Gradle 9.4.1 on the latest Java 26 patch release.
FROM gradle:9.4.1-jdk26 AS gradle-distribution

FROM amazoncorretto:26.0.2-alpine AS builder
COPY --from=gradle-distribution /opt/gradle /opt/gradle
ENV PATH="/opt/gradle/bin:${PATH}"
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src src/
RUN gradle clean build --no-daemon -x test --stacktrace 2>&1

FROM amazoncorretto:26.0.2-alpine
WORKDIR /app
RUN addgroup -S spring \
    && adduser -S -G spring spring \
    && apk add --no-cache curl
COPY --from=builder /app/build/libs/*.jar app.jar
RUN chown -R spring:spring /app
USER spring
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:${APP_PORT:-8080}/actuator/health || exit 1
ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]

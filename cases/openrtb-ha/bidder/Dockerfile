# Multi-stage build. Build context is cases/openrtb-ha/ (see docker-compose.yml), so the shared
# proto and all Gradle modules are visible to the build stage.
FROM gradle:9.7.1-jdk26 AS build
WORKDIR /src
COPY . .
RUN gradle --no-daemon :bidder:bootJar -x test

FROM amazoncorretto:26.0.2-alpine

RUN adduser -S -u 10001 bidder
WORKDIR /app
COPY --from=build /src/bidder/build/libs/bidder-0.1.0-SNAPSHOT.jar app.jar

USER 10001
EXPOSE 8080

# Percentage-based heap sizing keeps the JVM inside the container memory limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

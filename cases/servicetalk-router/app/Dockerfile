FROM gradle:9.4.1-jdk21-alpine AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src src/
RUN gradle clean installDist --no-daemon -x test

FROM amazoncorretto:21-alpine
WORKDIR /app
RUN apk add --no-cache curl
COPY --from=builder /app/build/install/multiregion-servicetalk-router ./
EXPOSE 8085
HEALTHCHECK --interval=5s --timeout=3s --retries=3 \
  CMD curl -f http://localhost:${ROUTER_PORT:-8085}/health || exit 1
ENTRYPOINT ["bin/multiregion-servicetalk-router"]

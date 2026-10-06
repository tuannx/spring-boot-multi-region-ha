FROM gradle:9.4.1-jdk21-alpine AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src src/
RUN gradle clean installDist --no-daemon -x test

# OpenTelemetry Java Agent for SigNoz tracing
ARG OTEL_JAVAAGENT_VERSION=2.28.1
ARG OTEL_JAVAAGENT_SHA256=faa89bdeebf9b1f52be4a4374689176717b02a59df2d8f8b6eb9aa39f9292589
RUN apk add --no-cache curl \
    && mkdir -p /otel \
    && curl --fail --silent --show-error --location --retry 3 \
       "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVAAGENT_VERSION}/opentelemetry-javaagent.jar" \
       --output /otel/opentelemetry-javaagent.jar \
    && echo "${OTEL_JAVAAGENT_SHA256}  /otel/opentelemetry-javaagent.jar" | sha256sum -c -

FROM amazoncorretto:21-alpine
WORKDIR /app
RUN apk add --no-cache curl
COPY --from=builder /app/build/install/multiregion-servicetalk-router ./
COPY --from=builder /otel/opentelemetry-javaagent.jar ./opentelemetry-javaagent.jar
EXPOSE 8085
HEALTHCHECK --interval=5s --timeout=3s --retries=3 \
  CMD curl -f http://localhost:${ROUTER_PORT:-8085}/health || exit 1
ENV OTEL_SDK_DISABLED=true
ENV JAVA_OPTS=""
ENTRYPOINT ["bin/multiregion-servicetalk-router"]

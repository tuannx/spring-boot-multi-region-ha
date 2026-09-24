# Build with Gradle 9.7.1 on the latest Java 26 patch release.
FROM gradle:9.7.1-jdk26 AS gradle-distribution

FROM amazoncorretto:26.0.2-alpine AS builder
COPY --from=gradle-distribution /opt/gradle /opt/gradle
ENV PATH="/opt/gradle/bin:${PATH}"
WORKDIR /app
RUN apk add --no-cache curl
COPY build.gradle.kts settings.gradle.kts ./
COPY src src/
RUN gradle clean build --no-daemon -x test --stacktrace 2>&1

RUN cp /app/build/libs/*[!plain].jar /app/app.jar \
    && java -XX:AOTMode=record -XX:AOTConfiguration=/app/app.aotconf \
            -Dspring.context.exit=onRefresh -Dspring.jpa.hibernate.ddl-auto=none \
            -jar /app/app.jar \
    && java -XX:AOTMode=create -XX:AOTConfiguration=/app/app.aotconf -XX:AOTCache=/app/app.aot \
            -jar /app/app.jar \
    && rm -f /app/app.aotconf

# Keep the agent version and checksum explicit so an application image build
# cannot silently pick up a different instrumentation bundle.
ARG OTEL_JAVAAGENT_VERSION=2.28.1
ARG OTEL_JAVAAGENT_SHA256=faa89bdeebf9b1f52be4a4374689176717b02a59df2d8f8b6eb9aa39f9292589
RUN mkdir -p /otel \
    && curl --fail --silent --show-error --location --retry 3 \
       "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVAAGENT_VERSION}/opentelemetry-javaagent.jar" \
       --output /otel/opentelemetry-javaagent.jar \
    && echo "${OTEL_JAVAAGENT_SHA256}  /otel/opentelemetry-javaagent.jar" | sha256sum -c -s -

FROM amazoncorretto:26.0.2-alpine
WORKDIR /app
RUN addgroup -S spring \
    && adduser -S -G spring spring \
    && apk add --no-cache curl
COPY --from=builder /app/app.jar app.jar
COPY --from=builder /app/app.aot app.aot
COPY --from=builder /otel/opentelemetry-javaagent.jar opentelemetry-javaagent.jar
RUN chown -R spring:spring /app
USER spring
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:${SERVER_PORT:-8080}/actuator/health || exit 1
ENV JAVA_OPTS="-XX:AOTMode=on -XX:AOTCache=/app/app.aot"
ENV OTEL_SDK_DISABLED=true
ENTRYPOINT ["sh", "-c", "exec java -Djava.security.egd=file:/dev/./urandom ${JAVA_OPTS} -jar app.jar"]

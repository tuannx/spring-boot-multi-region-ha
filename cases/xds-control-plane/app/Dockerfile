FROM gradle:9.4.1-jdk21-alpine AS builder
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY src src/
RUN gradle clean installDist --no-daemon -x test

FROM amazoncorretto:21-alpine
WORKDIR /app
COPY --from=builder /app/build/install/multiregion-xds-control-plane ./
# 18000: xDS (ADS over gRPC). 18001: plain HTTP admin for drain / restore / weights.
EXPOSE 18000 18001
ENTRYPOINT ["bin/multiregion-xds-control-plane"]

#!/usr/bin/env bash
set -euo pipefail

# Benchmark script comparing JVM baseline startup vs Project Leyden AOT Cache
IMAGE_NAME="${1:-multiregion-app:latest}"
CONTAINER_BASELINE="leyden-bench-baseline"
CONTAINER_LEYDEN="leyden-bench-aot"

cleanup() {
  docker rm -f "$CONTAINER_BASELINE" "$CONTAINER_LEYDEN" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "=========================================================="
echo " Project Leyden AOT Cache Benchmark"
echo " Image: $IMAGE_NAME"
echo "=========================================================="

cleanup

echo ""
echo "1. Measuring Baseline JVM startup..."
START_BASELINE=$(date +%s%N)
docker run -d --name "$CONTAINER_BASELINE" \
  -e JAVA_OPTS="" \
  -e OTEL_SDK_DISABLED=true \
  -p 18080:8080 \
  "$IMAGE_NAME" >/dev/null

# Wait for container healthcheck / actuator
ATTEMPTS=0
MAX_ATTEMPTS=60
BASELINE_READY=false
while [[ $ATTEMPTS -lt $MAX_ATTEMPTS ]]; do
  if curl -sf http://localhost:18080/actuator/health >/dev/null 2>&1; then
    BASELINE_READY=true
    break
  fi
  sleep 0.1
  ATTEMPTS=$((ATTEMPTS + 1))
done
END_BASELINE=$(date +%s%N)

if [[ "$BASELINE_READY" != "true" ]]; then
  echo "Baseline failed to become ready within timeout."
  docker logs "$CONTAINER_BASELINE" | tail -n 20
  exit 1
fi

BASELINE_MS=$(( (END_BASELINE - START_BASELINE) / 1000000 ))
echo "   Baseline time-to-healthy: ${BASELINE_MS} ms"

echo ""
echo "2. Measuring Project Leyden AOT Cache startup..."
START_LEYDEN=$(date +%s%N)
docker run -d --name "$CONTAINER_LEYDEN" \
  -e JAVA_OPTS="-XX:AOTMode=on -XX:AOTCache=/app/app.aot" \
  -e OTEL_SDK_DISABLED=true \
  -p 18081:8080 \
  "$IMAGE_NAME" >/dev/null

ATTEMPTS=0
LEYDEN_READY=false
while [[ $ATTEMPTS -lt $MAX_ATTEMPTS ]]; do
  if curl -sf http://localhost:18081/actuator/health >/dev/null 2>&1; then
    LEYDEN_READY=true
    break
  fi
  sleep 0.1
  ATTEMPTS=$((ATTEMPTS + 1))
done
END_LEYDEN=$(date +%s%N)

if [[ "$LEYDEN_READY" != "true" ]]; then
  echo "Leyden AOT container failed to become ready within timeout."
  docker logs "$CONTAINER_LEYDEN" | tail -n 20
  exit 1
fi

LEYDEN_MS=$(( (END_LEYDEN - START_LEYDEN) / 1000000 ))
echo "   Project Leyden time-to-healthy: ${LEYDEN_MS} ms"

echo ""
echo "=========================================================="
echo " Results Summary"
echo "=========================================================="
SPEEDUP=$(awk "BEGIN { printf \"%.1f\", $BASELINE_MS / $LEYDEN_MS }")
REDUCTION=$(awk "BEGIN { printf \"%.1f\", (($BASELINE_MS - $LEYDEN_MS) / $BASELINE_MS) * 100 }")
echo " Baseline JVM:       ${BASELINE_MS} ms"
echo " Project Leyden AOT: ${LEYDEN_MS} ms"
echo " Acceleration:       ${SPEEDUP}x faster (${REDUCTION}% reduction in startup latency)"
echo "=========================================================="

#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
FOUNDRYCTL_BIN="${FOUNDRYCTL_BIN:-foundryctl}"
SIGNOZ_RUNTIME_DIR="${SIGNOZ_RUNTIME_DIR:-${ROOT_DIR}/.observability/signoz}"
POURS_DIR="${SIGNOZ_RUNTIME_DIR}/pours"
SIGNOZ_COMPOSE="${POURS_DIR}/deployment/compose.yaml"
APP_PROJECT_NAME="${APP_PROJECT_NAME:-spring-ha-observability}"
APP_US_URL="http://localhost:${APP_US_HOST_PORT:-8080}"
APP_EU_URL="http://localhost:${APP_EU_HOST_PORT:-8081}"

wait_for_http() {
  local endpoint="$1"
  local attempts="$2"
  local response

  while (( attempts > 0 )); do
    if response="$(curl --fail --silent "${endpoint}")"; then
      return 0
    fi
    attempts=$((attempts - 1))
    sleep 2
  done

  echo "Timed out waiting for ${endpoint}" >&2
  return 1
}

if ! command -v "${FOUNDRYCTL_BIN}" >/dev/null 2>&1; then
  echo "foundryctl is required. Install it from https://signoz.io/docs/install/docker/" >&2
  exit 1
fi

mkdir -p "${POURS_DIR}"

"${FOUNDRYCTL_BIN}" forge \
  --file "${ROOT_DIR}/observability/casting.yaml" \
  --pours "${POURS_DIR}"

if [[ ! -f "${SIGNOZ_COMPOSE}" ]]; then
  echo "SigNoz Compose was not generated at ${SIGNOZ_COMPOSE}" >&2
  exit 1
fi

docker compose \
  --project-name signoz \
  --file "${SIGNOZ_COMPOSE}" \
  up -d --force-recreate

echo "Waiting for SigNoz API..."
wait_for_http http://localhost:9090/api/v1/health 60

if [[ -z "${OTEL_SERVICE_VERSION:-}" ]]; then
  OTEL_SERVICE_VERSION="$(git -C "${ROOT_DIR}" rev-parse --short HEAD 2>/dev/null || echo local)"
  export OTEL_SERVICE_VERSION
fi

docker compose \
  --project-name "${APP_PROJECT_NAME}" \
  --file "${ROOT_DIR}/docker-compose.yml" \
  --file "${ROOT_DIR}/docker-compose.observability.yml" \
  up -d --build

echo "Waiting for application health endpoints..."
wait_for_http "${APP_US_URL}/health" 90
wait_for_http "${APP_EU_URL}/health" 90

echo "SigNoz UI: http://localhost:9090"
echo "OTLP gRPC: localhost:4317"
echo "OTLP HTTP:  localhost:4318"
echo "Run APP_US_HOST_PORT=${APP_US_HOST_PORT:-8080} ./scripts/observability-verify.sh for the runtime proof."

#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SIGNOZ_RUNTIME_DIR="${SIGNOZ_RUNTIME_DIR:-${ROOT_DIR}/.observability/signoz}"
SIGNOZ_COMPOSE="${SIGNOZ_RUNTIME_DIR}/pours/deployment/compose.yaml"
APP_PROJECT_NAME="${APP_PROJECT_NAME:-spring-ha-observability}"
APP_US_URL="${APP_US_URL:-http://localhost:${APP_US_HOST_PORT:-8080}}"
APP_EU_URL="${APP_EU_URL:-http://localhost:${APP_EU_HOST_PORT:-8081}}"

wait_for_http() {
  local endpoint="$1"
  local attempts="$2"
  local response

  while (( attempts > 0 )); do
    if response="$(curl --fail --silent --show-error "${endpoint}")"; then
      printf '%s\n' "${response}"
      return 0
    fi
    attempts=$((attempts - 1))
    sleep 2
  done

  echo "Timed out waiting for ${endpoint}" >&2
  return 1
}

if [[ ! -f "${SIGNOZ_COMPOSE}" ]]; then
  echo "SigNoz is not generated yet. Run ./scripts/observability-up.sh first." >&2
  exit 1
fi

echo "-- SigNoz health --"
wait_for_http http://localhost:9090/api/v1/health 30

echo "-- application health --"
wait_for_http "${APP_US_URL}/health" 60
wait_for_http "${APP_EU_URL}/health" 60
echo

echo "-- running containers --"
docker compose \
  --project-name signoz \
  --file "${SIGNOZ_COMPOSE}" \
  ps --format table
docker compose \
  --project-name "${APP_PROJECT_NAME}" \
  --file "${ROOT_DIR}/docker-compose.yml" \
  --file "${ROOT_DIR}/docker-compose.observability.yml" \
  ps --format table

echo "Verification passed. Generate a few HTTP/JDBC requests, then open http://localhost:9090."

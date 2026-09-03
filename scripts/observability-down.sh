#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
SIGNOZ_RUNTIME_DIR="${SIGNOZ_RUNTIME_DIR:-${ROOT_DIR}/.observability/signoz}"
SIGNOZ_COMPOSE="${SIGNOZ_RUNTIME_DIR}/pours/deployment/compose.yaml"
APP_PROJECT_NAME="${APP_PROJECT_NAME:-spring-ha-observability}"

DOWN_ARGS=(down --remove-orphans)
if [[ "${PURGE_DATA:-false}" == "true" ]]; then
  DOWN_ARGS+=(--volumes)
fi

docker compose \
  --project-name "${APP_PROJECT_NAME}" \
  --file "${ROOT_DIR}/docker-compose.yml" \
  --file "${ROOT_DIR}/docker-compose.observability.yml" \
  "${DOWN_ARGS[@]}"

if [[ -f "${SIGNOZ_COMPOSE}" ]]; then
  docker compose \
    --project-name signoz \
    --file "${SIGNOZ_COMPOSE}" \
    "${DOWN_ARGS[@]}"
fi

if [[ "${PURGE_DATA:-false}" == "true" ]]; then
  echo "Stopped the HA and SigNoz stacks and removed their Compose volumes."
else
  echo "Stopped the HA and SigNoz stacks; data volumes were preserved."
fi

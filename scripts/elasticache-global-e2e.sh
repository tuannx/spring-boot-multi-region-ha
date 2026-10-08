#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${REPO_DIR}/cases/elasticache-global/docker-compose.yml"
COMPOSE_PROJECT="multiregion-elasticache"
CASE_COMPOSE=(docker compose -p "${COMPOSE_PROJECT}" -f "${COMPOSE_FILE}")

START_STACK=false
CLEANUP=false

usage() {
  cat <<'USAGE'
Usage: ./scripts/elasticache-global-e2e.sh [--start] [--cleanup|--keep]

  --start    Build and start the two-region Valkey case before acceptance.
  --cleanup  Remove this case's containers and volumes when the run finishes.
  --keep     Keep the environment running for inspection (the default).
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --start) START_STACK=true ;;
    --cleanup) CLEANUP=true ;;
    --keep) CLEANUP=false ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command is not installed: $1" >&2
    exit 1
  fi
}

for command_name in curl docker jq; do
  require_command "${command_name}"
done

print_diagnostics() {
  echo "ElastiCache (Valkey) case diagnostics:" >&2
  "${CASE_COMPOSE[@]}" ps >&2 || true
  "${CASE_COMPOSE[@]}" logs --tail 80 app-us app-eu valkey-us valkey-eu nginx-router >&2 || true
}

cleanup() {
  exit_code=$?
  if [[ ${exit_code} -ne 0 ]]; then
    print_diagnostics
  fi
  if [[ "${CLEANUP}" == true ]]; then
    "${CASE_COMPOSE[@]}" down --volumes --remove-orphans
  fi
  exit "${exit_code}"
}
trap cleanup EXIT

wait_for_health_region() {
  url=$1
  expected_region=$2
  attempts=${3:-90}

  for ((attempt = 1; attempt <= attempts; attempt++)); do
    response="$(curl --silent --show-error --max-time 8 "${url}" 2>/dev/null || true)"
    if [[ "$(jq -r '.status // empty' <<<"${response}" 2>/dev/null)" == "UP" ]] \
        && [[ "$(jq -r '.region // empty' <<<"${response}" 2>/dev/null)" == "${expected_region}" ]]; then
      return 0
    fi
    sleep 2
  done

  echo "Timed out waiting for ${url} to report UP in ${expected_region}" >&2
  return 1
}

# Polls until the item is visible and prints the visibility delay in ms.
wait_for_catalog_item() {
  url=$1
  item_id=$2
  expected_name=$3
  attempts=${4:-90}

  started_ms=$(($(date +%s) * 1000))
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    response="$(curl --silent --show-error --max-time 8 "${url}/api/catalog/${item_id}" 2>/dev/null || true)"
    if [[ "$(jq -r '.name // empty' <<<"${response}" 2>/dev/null)" == "${expected_name}" ]]; then
      now_ms=$(($(date +%s) * 1000))
      echo $((now_ms - started_ms))
      return 0
    fi
    sleep 1
  done

  echo "Timed out waiting for item ${item_id} at ${url}" >&2
  return 1
}

if [[ "${START_STACK}" == true ]]; then
  echo "Starting the two-region Valkey (ElastiCache Global style) case..."
  COMPOSE_PROGRESS=plain "${CASE_COMPOSE[@]}" up -d --build
fi

wait_for_health_region "http://localhost:8280/health" "us-east-1" 120
wait_for_health_region "http://localhost:8281/health" "eu-west-1" 120

run_suffix="$(date +%s)"
baseline_name="US baseline ${run_suffix}"
baseline_response="$(curl --fail --silent --show-error \
  -X POST "http://localhost:8200/api/catalog" \
  -H "X-Source-Region: us-east-1" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"${baseline_name}\",\"price\":19.99}")"
baseline_id="$(jq -er '.id' <<<"${baseline_response}")"
[[ "$(jq -r '.originRegion' <<<"${baseline_response}")" == "us-east-1" ]]

echo "Verifying asynchronous primary→replica replication for ${baseline_id}..."
replication_ms="$(wait_for_catalog_item "http://localhost:8281" "${baseline_id}" "${baseline_name}")"
echo "Replica visibility delay (stale-read window): ${replication_ms} ms"

echo "Stopping the US application and the US (primary) Valkey..."
"${CASE_COMPOSE[@]}" stop app-us valkey-us
wait_for_health_region "http://localhost:8200/health" "eu-west-1"

echo "Promoting the EU replica (REPLICAOF NO ONE)..."
"${CASE_COMPOSE[@]}" exec -T valkey-eu valkey-cli REPLICAOF NO ONE

failover_name="EU after promotion ${run_suffix}"
failover_response="$(curl --fail --silent --show-error \
  -X POST "http://localhost:8200/api/catalog" \
  -H "X-Source-Region: us-east-1" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"${failover_name}\",\"price\":29.99}")"
failover_id="$(jq -er '.id' <<<"${failover_response}")"
[[ "$(jq -r '.originRegion' <<<"${failover_response}")" == "eu-west-1" ]]

echo "Restarting the US Valkey as a replica of the promoted EU primary..."
"${CASE_COMPOSE[@]}" start valkey-us
for ((attempt = 1; attempt <= 60; attempt++)); do
  if [[ "$("${CASE_COMPOSE[@]}" exec -T valkey-us valkey-cli ping 2>/dev/null || true)" == "PONG" ]]; then
    break
  fi
  sleep 1
done
"${CASE_COMPOSE[@]}" exec -T valkey-us valkey-cli REPLICAOF valkey-eu 6379

"${CASE_COMPOSE[@]}" start app-us
wait_for_health_region "http://localhost:8280/health" "us-east-1" 120

echo "Verifying promoted-region write replicates back to US for ${failover_id}..."
wait_for_catalog_item "http://localhost:8280" "${failover_id}" "${failover_name}" 120 >/dev/null

curl --fail --silent --show-error -X DELETE \
  "http://localhost:8281/api/catalog/${baseline_id}" >/dev/null || true
curl --fail --silent --show-error -X DELETE \
  "http://localhost:8281/api/catalog/${failover_id}" >/dev/null || true

echo "ElastiCache (Valkey) multi-region acceptance passed:"
echo "  - two-region Valkey primary/replica topology is UP"
echo "  - US write replicated asynchronously to the EU replica (window measured above)"
echo "  - EU replica promoted and accepted writes during a US outage"
echo "  - US rejoined as replica and saw the promoted-region write"

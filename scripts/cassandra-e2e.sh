#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
COMPOSE_FILE="${REPO_DIR}/cases/cassandra/docker-compose.yml"
COMPOSE_PROJECT="multiregion-cassandra"
CASSANDRA_COMPOSE=(docker compose -p "${COMPOSE_PROJECT}" -f "${COMPOSE_FILE}")

START_STACK=false
CLEANUP=false

usage() {
  cat <<'USAGE'
Usage: ./scripts/cassandra-e2e.sh [--start] [--cleanup|--keep]

  --start    Build and start the six-node Cassandra case before acceptance.
  --cleanup  Remove this case's containers and volumes when the run finishes.
  --keep     Keep the environment running for inspection (the default).
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --start)
      START_STACK=true
      ;;
    --cleanup)
      CLEANUP=true
      ;;
    --keep)
      CLEANUP=false
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
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
  echo "Cassandra case diagnostics:" >&2
  "${CASSANDRA_COMPOSE[@]}" ps >&2 || true
  "${CASSANDRA_COMPOSE[@]}" logs --tail 80 app-us app-eu nginx-router schema-init >&2 || true
}

cleanup() {
  exit_code=$?
  if [[ ${exit_code} -ne 0 ]]; then
    print_diagnostics
  fi
  if [[ "${CLEANUP}" == true ]]; then
    "${CASSANDRA_COMPOSE[@]}" down --volumes --remove-orphans
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

wait_for_catalog_item() {
  url=$1
  item_id=$2
  expected_name=$3
  attempts=${4:-90}

  for ((attempt = 1; attempt <= attempts; attempt++)); do
    response="$(curl --silent --show-error --max-time 8 "${url}/api/catalog/${item_id}" 2>/dev/null || true)"
    if [[ "$(jq -r '.name // empty' <<<"${response}" 2>/dev/null)" == "${expected_name}" ]]; then
      return 0
    fi
    sleep 2
  done

  echo "Timed out waiting for item ${item_id} at ${url}" >&2
  return 1
}

wait_for_container_health() {
  container_name=$1
  attempts=${2:-90}

  for ((attempt = 1; attempt <= attempts; attempt++)); do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
      "${container_name}" 2>/dev/null || true)"
    if [[ "${status}" == "healthy" ]]; then
      return 0
    fi
    sleep 2
  done

  echo "Timed out waiting for ${container_name} to become healthy" >&2
  return 1
}

wait_for_six_nodes() {
  for ((attempt = 1; attempt <= 90; attempt++)); do
    up_nodes="$("${CASSANDRA_COMPOSE[@]}" exec -T cassandra-eu-1 nodetool status 2>/dev/null \
      | awk '/^UN/ { count++ } END { print count+0 }')"
    if [[ "${up_nodes}" -eq 6 ]]; then
      return 0
    fi
    sleep 2
  done

  echo "Timed out waiting for all six Cassandra nodes to return to UN state" >&2
  return 1
}

if [[ "${START_STACK}" == true ]]; then
  echo "Starting the two-DC Cassandra case..."
  COMPOSE_PROGRESS=plain "${CASSANDRA_COMPOSE[@]}" up -d --build
fi

wait_for_health_region "http://localhost:8180/health" "us-east-1" 150
wait_for_health_region "http://localhost:8181/health" "eu-west-1" 150
wait_for_six_nodes

run_suffix="$(date +%s)"
baseline_name="US baseline ${run_suffix}"
baseline_response="$(curl --fail --silent --show-error \
  -X POST "http://localhost:8100/api/catalog" \
  -H "X-Source-Region: us-east-1" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"${baseline_name}\",\"price\":19.99}")"
baseline_id="$(jq -er '.id' <<<"${baseline_response}")"
[[ "$(jq -r '.originRegion' <<<"${baseline_response}")" == "us-east-1" ]]

echo "Verifying asynchronous cross-DC replication for ${baseline_id}..."
wait_for_catalog_item "http://localhost:8181" "${baseline_id}" "${baseline_name}"

echo "Stopping the US application and all three US Cassandra nodes..."
"${CASSANDRA_COMPOSE[@]}" stop app-us cassandra-us-3 cassandra-us-2 cassandra-us-1
wait_for_health_region "http://localhost:8100/health" "eu-west-1"

failover_name="EU during US outage ${run_suffix}"
failover_response="$(curl --fail --silent --show-error \
  -X POST "http://localhost:8100/api/catalog" \
  -H "X-Source-Region: us-east-1" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"${failover_name}\",\"price\":29.99}")"
failover_id="$(jq -er '.id' <<<"${failover_response}")"
[[ "$(jq -r '.originRegion' <<<"${failover_response}")" == "eu-west-1" ]]

echo "Restarting the US Cassandra datacenter..."
"${CASSANDRA_COMPOSE[@]}" start cassandra-us-1
wait_for_container_health "multiregion-cassandra-us-1" 150
"${CASSANDRA_COMPOSE[@]}" start cassandra-us-2
wait_for_container_health "multiregion-cassandra-us-2" 150
"${CASSANDRA_COMPOSE[@]}" start cassandra-us-3
wait_for_container_health "multiregion-cassandra-us-3" 150
wait_for_six_nodes

"${CASSANDRA_COMPOSE[@]}" start app-us
wait_for_health_region "http://localhost:8180/health" "us-east-1" 150

echo "Verifying hinted handoff/recovery for ${failover_id}..."
wait_for_catalog_item "http://localhost:8180" "${failover_id}" "${failover_name}" 150

curl --fail --silent --show-error -X DELETE \
  "http://localhost:8181/api/catalog/${baseline_id}" >/dev/null
curl --fail --silent --show-error -X DELETE \
  "http://localhost:8181/api/catalog/${failover_id}" >/dev/null

echo "Cassandra multi-region acceptance passed:"
echo "  - six-node, two-datacenter topology is UP"
echo "  - LOCAL_QUORUM write replicated from US to EU"
echo "  - global traffic failed over to EU during a complete US outage"
echo "  - EU write became visible in US after datacenter recovery"

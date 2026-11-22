#!/usr/bin/env bash
# Acceptance check for the Spock active-active option: write one row on
# postgres-us and one on postgres-eu, then prove that BOTH nodes converge
# to BOTH rows. Also prints the conflict bookkeeping (spock.resolutions and
# spock.exception_log) so a "successful" run cannot hide resolved or
# unresolvable conflicts.
#
# Prerequisite: ./scripts/spock-setup.sh has completed.
set -euo pipefail

cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f docker-compose.spock.yml)
DB_NAME="${DB_NAME:-appdb}"
DB_USER="${DB_USER:-appuser}"

psql_node() { # $1 = service, $2 = SQL
  "${COMPOSE[@]}" exec -T "$1" psql -U "$DB_USER" -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 -tAc "$2"
}

STAMP="$(date +%s)"
US_PAYLOAD="verify-from-us-${STAMP}"
EU_PAYLOAD="verify-from-eu-${STAMP}"

echo "==> Writing one row on each node"
US_ID="$(psql_node postgres-us "INSERT INTO spock_demo (payload, region) VALUES ('${US_PAYLOAD}', 'us-east-1') RETURNING id")"
EU_ID="$(psql_node postgres-eu "INSERT INTO spock_demo (payload, region) VALUES ('${EU_PAYLOAD}', 'eu-west-1') RETURNING id")"
echo "postgres-us wrote id=${US_ID} (${US_PAYLOAD})"
echo "postgres-eu wrote id=${EU_ID} (${EU_PAYLOAD})"
if [ "$US_ID" = "$EU_ID" ]; then
  echo "FAIL: snowflake ids collided across nodes - check snowflake.node is unique per node" >&2
  exit 1
fi

echo "==> Waiting for convergence (up to 30s)"
converged=0
for _ in $(seq 1 30); do
  us_seen="$(psql_node postgres-us "SELECT count(*) FROM spock_demo WHERE id IN (${US_ID}, ${EU_ID})")"
  eu_seen="$(psql_node postgres-eu "SELECT count(*) FROM spock_demo WHERE id IN (${US_ID}, ${EU_ID})")"
  if [ "$us_seen" = "2" ] && [ "$eu_seen" = "2" ]; then
    converged=1
    break
  fi
  sleep 1
done

echo "-- spock_demo as seen by postgres-us:"
psql_node postgres-us "SELECT id, payload, region FROM spock_demo WHERE id IN (${US_ID}, ${EU_ID}) ORDER BY id"
echo "-- spock_demo as seen by postgres-eu:"
psql_node postgres-eu "SELECT id, payload, region FROM spock_demo WHERE id IN (${US_ID}, ${EU_ID}) ORDER BY id"

echo "==> Conflict bookkeeping"
for svc in postgres-us postgres-eu; do
  res="$(psql_node "$svc" "SELECT count(*) FROM spock.resolutions")"
  exc="$(psql_node "$svc" "SELECT count(*) FROM spock.exception_log")"
  echo "${svc}: spock.resolutions rows=${res}, spock.exception_log rows=${exc}"
done

if [ "$converged" != "1" ]; then
  echo "FAIL: nodes did not converge within 30s" >&2
  exit 1
fi
echo "PASS: both nodes hold both rows - bidirectional active-active replication is live."

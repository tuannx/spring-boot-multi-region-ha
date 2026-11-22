#!/usr/bin/env bash
# Bootstrap the Spock active-active option (docker-compose.spock.yml):
# starts both pgEdge nodes, installs the spock + snowflake extensions,
# creates the spock_demo table on both nodes, registers Spock nodes n1/n2,
# and creates the two subscriptions that make replication bidirectional.
#
# Safe to re-run: every step is guarded, existing nodes/subscriptions and
# the demo table are left untouched.
#
# The demo table is deliberately NOT the Flyway-managed products table.
# See docs/spock-active-active.md for why (snowflake ids vs JPA IDENTITY,
# the single-writer fencing trigger, Flyway running on both nodes).
set -euo pipefail

cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f docker-compose.spock.yml)
DB_NAME="${DB_NAME:-appdb}"
DB_USER="${DB_USER:-appuser}"
DB_PASS="${DB_PASS:-apppass}"

psql_node() { # $1 = service (postgres-us|postgres-eu), $2 = SQL
  "${COMPOSE[@]}" exec -T "$1" psql -U "$DB_USER" -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 -tAc "$2"
}

wait_ready() { # $1 = service
  local svc="$1" tries=0
  until "${COMPOSE[@]}" exec -T "$svc" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1; do
    tries=$((tries + 1))
    if [ "$tries" -ge 60 ]; then
      echo "ERROR: $svc did not become ready" >&2
      exit 1
    fi
    sleep 2
  done
  echo "$svc is ready"
}

echo "==> Starting Spock nodes"
"${COMPOSE[@]}" up -d postgres-us postgres-eu db-tools
wait_ready postgres-us
wait_ready postgres-eu

# Extensions and the demo table are created identically on BOTH nodes before
# any subscription exists. Spock DDL replication stays OFF in this option:
# schema is code, applied per node, never streamed (see the docs for the
# missing queue-flush hazard that motivates this).
SCHEMA_SQL='
CREATE EXTENSION IF NOT EXISTS spock;
CREATE EXTENSION IF NOT EXISTS snowflake;
CREATE TABLE IF NOT EXISTS public.spock_demo (
  id         BIGINT PRIMARY KEY DEFAULT snowflake.nextval(),
  payload    TEXT NOT NULL,
  region     VARCHAR(50) NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
'

echo "==> Installing extensions and demo table on both nodes"
psql_node postgres-us "$SCHEMA_SQL" >/dev/null
psql_node postgres-eu "$SCHEMA_SQL" >/dev/null

# Node registration. appuser is the bootstrap superuser of the pgEdge image,
# which satisfies Spock's superuser requirement for configuration.
US_DSN="host=postgres-us port=5432 dbname=${DB_NAME} user=${DB_USER} password=${DB_PASS}"
EU_DSN="host=postgres-eu port=5432 dbname=${DB_NAME} user=${DB_USER} password=${DB_PASS}"

if [ -z "$(psql_node postgres-us "SELECT 1 FROM spock.node WHERE node_name = 'n1'")" ]; then
  echo "==> Creating Spock node n1 on postgres-us"
  psql_node postgres-us "SELECT spock.node_create(node_name := 'n1', dsn := '${US_DSN}')" >/dev/null
fi
if [ -z "$(psql_node postgres-eu "SELECT 1 FROM spock.node WHERE node_name = 'n2'")" ]; then
  echo "==> Creating Spock node n2 on postgres-eu"
  psql_node postgres-eu "SELECT spock.node_create(node_name := 'n2', dsn := '${EU_DSN}')" >/dev/null
fi

# Only spock_demo joins the default replication set. In particular the
# Flyway-managed tables (products, failover_control, flyway_schema_history)
# must never be added: they encode the single-writer model.
echo "==> Adding spock_demo to the default replication set on both nodes"
for svc in postgres-us postgres-eu; do
  psql_node "$svc" "
    DO \$\$
    BEGIN
      PERFORM spock.repset_add_table('default', 'spock_demo');
    EXCEPTION WHEN OTHERS THEN
      RAISE NOTICE 'repset_add_table skipped on ${svc}: %', SQLERRM;
    END
    \$\$;" >/dev/null
done

# Subscriptions, in the order the pgEdge two-node guide prescribes:
# the second node's subscription first, then the reverse one.
if [ -z "$(psql_node postgres-eu "SELECT 1 FROM spock.subscription WHERE sub_name = 'sub_eu_us'")" ]; then
  echo "==> Creating subscription sub_eu_us (EU subscribes to US)"
  psql_node postgres-eu "SELECT spock.sub_create(subscription_name := 'sub_eu_us', provider_dsn := '${US_DSN}')" >/dev/null
  psql_node postgres-eu "SELECT spock.sub_wait_for_sync('sub_eu_us')" >/dev/null
fi
if [ -z "$(psql_node postgres-us "SELECT 1 FROM spock.subscription WHERE sub_name = 'sub_us_eu'")" ]; then
  echo "==> Creating subscription sub_us_eu (US subscribes to EU)"
  psql_node postgres-us "SELECT spock.sub_create(subscription_name := 'sub_us_eu', provider_dsn := '${EU_DSN}')" >/dev/null
  psql_node postgres-us "SELECT spock.sub_wait_for_sync('sub_us_eu')" >/dev/null
fi

echo "==> Cluster state"
echo "-- postgres-us nodes:";        psql_node postgres-us "SELECT node_name FROM spock.node ORDER BY 1"
echo "-- postgres-us subscriptions:"; psql_node postgres-us "SELECT sub_name FROM spock.subscription ORDER BY 1"
echo "-- postgres-eu nodes:";        psql_node postgres-eu "SELECT node_name FROM spock.node ORDER BY 1"
echo "-- postgres-eu subscriptions:"; psql_node postgres-eu "SELECT sub_name FROM spock.subscription ORDER BY 1"
echo "Done. Run ./scripts/spock-verify.sh to prove bidirectional writes converge."

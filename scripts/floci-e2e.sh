#!/usr/bin/env bash
set -euo pipefail
unset CDPATH

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
COMPOSE_FILE_PATH="${ROOT_DIR}/docker-compose.floci.yml"
COMPOSE_PROJECT_NAME="spring-ha-floci"
TERRAFORM_DIR="${ROOT_DIR}/infra/floci/terraform"
REPORT_DIR="${ROOT_DIR}/reports/floci"
FLOCI_US_ENDPOINT="${FLOCI_US_ENDPOINT:-http://localhost:4566}"
FLOCI_EU_ENDPOINT="${FLOCI_EU_ENDPOINT:-http://localhost:4567}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-240}"
FLOCI_DATABASE_PASSWORD="${FLOCI_DATABASE_PASSWORD:-AppPass123!}"
FLOCI_RABBITMQ_PASSWORD="${FLOCI_RABBITMQ_PASSWORD:-AppPass123456!}"
FLOCI_RABBITMQ_US_HOST_PORT="${FLOCI_RABBITMQ_US_HOST_PORT:-5672}"
FLOCI_RABBITMQ_EU_HOST_PORT="${FLOCI_RABBITMQ_EU_HOST_PORT:-5673}"
FLOCI_RABBITMQ_US_MANAGEMENT_HOST_PORT="${FLOCI_RABBITMQ_US_MANAGEMENT_HOST_PORT:-15672}"
FLOCI_RABBITMQ_EU_MANAGEMENT_HOST_PORT="${FLOCI_RABBITMQ_EU_MANAGEMENT_HOST_PORT:-15673}"
APP_US_URL="${APP_US_URL:-http://localhost:${APP_US_HOST_PORT:-8080}}"
APP_EU_URL="${APP_EU_URL:-http://localhost:${APP_EU_HOST_PORT:-8081}}"
ROUTER_URL="${ROUTER_URL:-http://localhost:${ROUTER_HOST_PORT:-8000}}"
RABBITMQ_US_MANAGEMENT_URL="${RABBITMQ_US_MANAGEMENT_URL:-http://localhost:${FLOCI_RABBITMQ_US_MANAGEMENT_HOST_PORT}}"
RABBITMQ_EU_MANAGEMENT_URL="${RABBITMQ_EU_MANAGEMENT_URL:-http://localhost:${FLOCI_RABBITMQ_EU_MANAGEMENT_HOST_PORT}}"
CLEANUP=true
TERRAFORM_APPLIED=false
US_BROKER_ID=""
EU_BROKER_ID=""

export FLOCI_RABBITMQ_US_HOST_PORT
export FLOCI_RABBITMQ_EU_HOST_PORT
export FLOCI_RABBITMQ_US_MANAGEMENT_HOST_PORT
export FLOCI_RABBITMQ_EU_MANAGEMENT_HOST_PORT

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_DEFAULT_REGION=us-east-1
export AWS_EC2_METADATA_DISABLED=true
export AWS_PAGER=""

usage() {
  cat <<'EOF'
Usage: scripts/floci-e2e.sh [--cleanup|--keep]

Provision two regional PostgreSQL instances and two RabbitMQ brokers through
Floci's AWS-compatible APIs, wire the application to the returned data-plane
endpoints, and run the canonical fenced failover and queue-takeover acceptance.

Options:
  --cleanup   Destroy Terraform resources and containers on exit (default).
  --keep      Keep the verified Floci environment running for investigation.
  -h, --help  Show this help.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --cleanup)
      CLEANUP=true
      shift
      ;;
    --keep)
      CLEANUP=false
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown option: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf 'Missing required command: %s\n' "$1" >&2
    exit 127
  fi
}

for command_name in aws curl docker jq terraform; do
  require_cmd "$command_name"
done

compose() {
  docker compose \
    --project-name "$COMPOSE_PROJECT_NAME" \
    --file "$COMPOSE_FILE_PATH" \
    "$@"
}

terraform_in_floci() {
  TF_IN_AUTOMATION=1 \
  AWS_ACCESS_KEY_ID=test \
  AWS_SECRET_ACCESS_KEY=test \
  AWS_DEFAULT_REGION=us-east-1 \
  terraform -chdir="$TERRAFORM_DIR" "$@"
}

capture_evidence() {
  mkdir -p "$REPORT_DIR"
  compose ps --all > "$REPORT_DIR/compose-ps.txt" 2>&1 || true
  compose logs --no-color > "$REPORT_DIR/compose.log" 2>&1 || true
  docker ps --filter "name=floci-" --format '{{json .}}' \
    > "$REPORT_DIR/floci-managed-containers.jsonl" 2>&1 || true
  if [[ -d "$TERRAFORM_DIR/.terraform" ]]; then
    terraform_in_floci output -json > "$REPORT_DIR/terraform-output.json" 2>&1 || true
  fi
}

cleanup() {
  local status=$?
  capture_evidence

  if [[ "$CLEANUP" == "true" ]]; then
    compose stop app-us app-eu nginx-router postgres-us postgres-eu \
      rabbitmq-us rabbitmq-eu db-tools >/dev/null 2>&1 || true
    if [[ "$TERRAFORM_APPLIED" == "true" ]]; then
      terraform_in_floci destroy \
        -auto-approve \
        -input=false \
        -var="floci_us_endpoint=$FLOCI_US_ENDPOINT" \
        -var="floci_eu_endpoint=$FLOCI_EU_ENDPOINT" \
        -var="database_password=$FLOCI_DATABASE_PASSWORD" \
        >/dev/null 2>&1 || true
    fi
    if [[ -n "$US_BROKER_ID" ]]; then
      aws --endpoint-url "$FLOCI_US_ENDPOINT" --region us-east-1 \
        mq delete-broker --broker-id "$US_BROKER_ID" >/dev/null 2>&1 || true
    fi
    if [[ -n "$EU_BROKER_ID" ]]; then
      aws --endpoint-url "$FLOCI_EU_ENDPOINT" --region eu-west-1 \
        mq delete-broker --broker-id "$EU_BROKER_ID" >/dev/null 2>&1 || true
    fi
    compose down --volumes --remove-orphans >/dev/null 2>&1 || true
  fi

  return "$status"
}
trap cleanup EXIT

wait_for_floci() {
  local endpoint="$1"
  local region="$2"
  local deadline=$((SECONDS + TIMEOUT_SECONDS))
  until curl --fail --silent "$endpoint/_localstack/health" >/dev/null; do
    if (( SECONDS >= deadline )); then
      printf 'Floci %s did not become ready within %ss\n' \
        "$region" "$TIMEOUT_SECONDS" >&2
      return 1
    fi
    sleep 1
  done
}

wait_for_postgres() {
  local host="$1"
  local deadline=$((SECONDS + TIMEOUT_SECONDS))
  until docker exec -e "PGPASSWORD=$FLOCI_DATABASE_PASSWORD" spring-ha-floci-db-tools \
      pg_isready -h "$host" -p 5432 -U appuser -d appdb >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      printf 'PostgreSQL proxy %s did not become ready within %ss\n' \
        "$host" "$TIMEOUT_SECONDS" >&2
      return 1
    fi
    sleep 1
  done
}

create_rabbitmq_broker() {
  local endpoint="$1"
  local region="$2"
  local name="$3"
  local users_json
  users_json="$(jq -n \
    --arg username appuser \
    --arg password "$FLOCI_RABBITMQ_PASSWORD" \
    '[{Username: $username, Password: $password}]')"

  aws --endpoint-url "$endpoint" --region "$region" mq create-broker \
    --broker-name "$name" \
    --engine-type RABBITMQ \
    --engine-version 3.13 \
    --host-instance-type mq.t3.micro \
    --deployment-mode SINGLE_INSTANCE \
    --auto-minor-version-upgrade \
    --no-publicly-accessible \
    --users "$users_json" \
    | jq -er '.BrokerId'
}

wait_for_broker() {
  local endpoint="$1"
  local region="$2"
  local broker_id="$3"
  local state=""
  local deadline=$((SECONDS + TIMEOUT_SECONDS))

  until [[ "$state" == "RUNNING" ]]; do
    state="$(aws --endpoint-url "$endpoint" --region "$region" \
      mq describe-broker --broker-id "$broker_id" \
      | jq -r '.BrokerState')"
    if (( SECONDS >= deadline )); then
      printf 'RabbitMQ broker %s did not become RUNNING within %ss\n' \
        "$broker_id" "$TIMEOUT_SECONDS" >&2
      return 1
    fi
    sleep 1
  done
}

cd "$ROOT_DIR"
mkdir -p "$REPORT_DIR"

compose up --detach floci-us floci-eu
wait_for_floci "$FLOCI_US_ENDPOINT" us-east-1
wait_for_floci "$FLOCI_EU_ENDPOINT" eu-west-1

terraform_in_floci init -input=false
terraform_in_floci validate
TERRAFORM_APPLIED=true
terraform_in_floci apply \
  -auto-approve \
  -input=false \
  -var="floci_us_endpoint=$FLOCI_US_ENDPOINT" \
  -var="floci_eu_endpoint=$FLOCI_EU_ENDPOINT" \
  -var="database_password=$FLOCI_DATABASE_PASSWORD"

# Floci 2.0.1 exposes RabbitMQ users from DescribeBroker, which makes the
# Terraform AWS provider call the unsupported standalone DescribeUser API.
# Provision through the same AWS-compatible endpoint until floci-io/floci#1951
# is released, then move these brokers back into the Terraform module.
US_BROKER_ID="$(create_rabbitmq_broker \
  "$FLOCI_US_ENDPOINT" us-east-1 rabbitmq-us)"
EU_BROKER_ID="$(create_rabbitmq_broker \
  "$FLOCI_EU_ENDPOINT" eu-west-1 rabbitmq-eu)"
wait_for_broker "$FLOCI_US_ENDPOINT" us-east-1 "$US_BROKER_ID"
wait_for_broker "$FLOCI_EU_ENDPOINT" eu-west-1 "$EU_BROKER_ID"

export FLOCI_DATABASE_US_PORT
export FLOCI_DATABASE_EU_PORT
export FLOCI_RABBITMQ_US_HOST
export FLOCI_RABBITMQ_US_PORT
export FLOCI_RABBITMQ_EU_HOST
export FLOCI_RABBITMQ_EU_PORT
export FLOCI_DATABASE_PASSWORD
export FLOCI_RABBITMQ_PASSWORD

FLOCI_DATABASE_US_PORT="$(terraform_in_floci output -raw database_us_port)"
FLOCI_DATABASE_EU_PORT="$(terraform_in_floci output -raw database_eu_port)"
FLOCI_RABBITMQ_US_ENDPOINT="$(aws --endpoint-url "$FLOCI_US_ENDPOINT" \
  --region us-east-1 mq describe-broker --broker-id "$US_BROKER_ID" \
  | jq -er '.BrokerInstances[0].Endpoints[0]')"
FLOCI_RABBITMQ_EU_ENDPOINT="$(aws --endpoint-url "$FLOCI_EU_ENDPOINT" \
  --region eu-west-1 mq describe-broker --broker-id "$EU_BROKER_ID" \
  | jq -er '.BrokerInstances[0].Endpoints[0]')"
FLOCI_RABBITMQ_US_HOST="${FLOCI_RABBITMQ_US_ENDPOINT#amqp://}"
FLOCI_RABBITMQ_US_PORT="${FLOCI_RABBITMQ_US_HOST##*:}"
FLOCI_RABBITMQ_US_HOST="${FLOCI_RABBITMQ_US_HOST%:*}"
FLOCI_RABBITMQ_EU_HOST="${FLOCI_RABBITMQ_EU_ENDPOINT#amqp://}"
FLOCI_RABBITMQ_EU_PORT="${FLOCI_RABBITMQ_EU_HOST##*:}"
FLOCI_RABBITMQ_EU_HOST="${FLOCI_RABBITMQ_EU_HOST%:*}"

compose up --detach postgres-us postgres-eu rabbitmq-us rabbitmq-eu db-tools
wait_for_postgres postgres-us
wait_for_postgres postgres-eu

docker exec -i -e "PGPASSWORD=$FLOCI_DATABASE_PASSWORD" spring-ha-floci-db-tools \
  psql -v ON_ERROR_STOP=1 -h postgres-us -p 5432 -U appuser -d appdb \
  < "$ROOT_DIR/docker/init/us/01-init.sql"
docker exec -i -e "PGPASSWORD=$FLOCI_DATABASE_PASSWORD" spring-ha-floci-db-tools \
  psql -v ON_ERROR_STOP=1 -h postgres-eu -p 5432 -U appuser -d appdb \
  < "$ROOT_DIR/docker/init/eu/01-init.sql"

aws --endpoint-url "$FLOCI_US_ENDPOINT" --region us-east-1 \
  rds describe-db-instances --db-instance-identifier postgres-us \
  > "$REPORT_DIR/rds-us.json"
aws --endpoint-url "$FLOCI_EU_ENDPOINT" --region eu-west-1 \
  rds describe-db-instances --db-instance-identifier postgres-eu \
  > "$REPORT_DIR/rds-eu.json"

aws --endpoint-url "$FLOCI_US_ENDPOINT" --region us-east-1 \
  mq describe-broker --broker-id "$US_BROKER_ID" \
  > "$REPORT_DIR/mq-us.json"
aws --endpoint-url "$FLOCI_EU_ENDPOINT" --region eu-west-1 \
  mq describe-broker --broker-id "$EU_BROKER_ID" \
  > "$REPORT_DIR/mq-eu.json"

jq -e '.DBInstances[0].DBInstanceStatus == "available"' \
  "$REPORT_DIR/rds-us.json" "$REPORT_DIR/rds-eu.json" >/dev/null
jq -e '.BrokerState == "RUNNING"' \
  "$REPORT_DIR/mq-us.json" "$REPORT_DIR/mq-eu.json" >/dev/null

compose up --detach --build app-us app-eu nginx-router

COMPOSE_FILE="$COMPOSE_FILE_PATH" \
COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT_NAME" \
APP_US_CONTAINER=multiregion-floci-app-us \
APP_EU_CONTAINER=multiregion-floci-app-eu \
APP_US_URL="$APP_US_URL" \
APP_EU_URL="$APP_EU_URL" \
ROUTER_URL="$ROUTER_URL" \
DB_TOOL_CONTAINER=spring-ha-floci-db-tools \
DB_USER=appuser \
DB_PASSWORD="$FLOCI_DATABASE_PASSWORD" \
DB_NAME=appdb \
US_DB_HOST=postgres-us \
US_DB_PORT=5432 \
EU_DB_HOST=postgres-eu \
EU_DB_PORT=5432 \
RABBITMQ_USER=appuser \
RABBITMQ_PASS="$FLOCI_RABBITMQ_PASSWORD" \
RABBITMQ_US_MANAGEMENT_URL="$RABBITMQ_US_MANAGEMENT_URL" \
RABBITMQ_EU_MANAGEMENT_URL="$RABBITMQ_EU_MANAGEMENT_URL" \
TIMEOUT_SECONDS="$TIMEOUT_SECONDS" \
./scripts/e2e-acceptance.sh --verify-failover

capture_evidence
printf 'Floci Terraform and application E2E acceptance passed.\n'

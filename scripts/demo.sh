#!/usr/bin/env bash
# ============================================
# One-shot WOW demo: guided multi-region HA story
# ============================================
# Runs the full narrative end to end:
#   1. Baseline (both regions UP, writer = postgres-us)
#   2. Writer/reader split proof (write via EU, read from both)
#   3. Fencing proof (EU activation refused while US holds writer)
#   4. Switchover (fence US, promote EU, writes follow) + measured RTO
#   5. Optional: kill old writer, EU keeps serving
#
# Open the live console during the run:
#   http://localhost:8000/demo.html
#
# Usage: ./scripts/demo.sh [--start|--reset] [--kill] [--pause] [--open] [--cleanup]
set -euo pipefail
unset CDPATH

START_STACK=false
RESET_STACK=false
CLEANUP_STACK=false
KILL_PHASE=false
PAUSE=false
OPEN_BROWSER=false
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-180}"
APP_US_URL="${APP_US_URL:-http://localhost:${APP_US_HOST_PORT:-8080}}"
APP_EU_URL="${APP_EU_URL:-http://localhost:${APP_EU_HOST_PORT:-8081}}"
ROUTER_URL="${ROUTER_URL:-http://localhost:${ROUTER_HOST_PORT:-8000}}"
US_DB_CONTAINER="${US_DB_CONTAINER:-multiregion-us}"
EU_DB_CONTAINER="${EU_DB_CONTAINER:-multiregion-eu}"
APP_US_CONTAINER="${APP_US_CONTAINER:-multiregion-app-us}"
DB_USER="${DB_USER:-appuser}"
DB_NAME="${DB_NAME:-appdb}"

usage() {
  cat <<USAGE
Usage: $0 [--start|--reset] [--kill] [--pause] [--open] [--cleanup]

Options:
  --start    Build and start the Docker Compose stack before the demo.
  --reset    Fresh stack (down -v + up --build) before the demo.
  --kill     Include the kill-old-writer phase after switchover.
  --pause    Wait for Enter between phases (presentation mode).
  --open     Open the live dashboard in a browser.
  --cleanup  Stop the stack when the demo exits.

Environment overrides:
  APP_US_URL, APP_EU_URL, ROUTER_URL
  APP_US_HOST_PORT, APP_EU_HOST_PORT, ROUTER_HOST_PORT (host port bindings)
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --start) START_STACK=true; shift ;;
    --reset) RESET_STACK=true; START_STACK=true; shift ;;
    --cleanup) CLEANUP_STACK=true; shift ;;
    --kill) KILL_PHASE=true; shift ;;
    --pause) PAUSE=true; shift ;;
    --open) OPEN_BROWSER=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ "$START_STACK" == "true" || "$CLEANUP_STACK" == "true" ]]; then
  command -v docker >/dev/null 2>&1 || { echo "docker is required" >&2; exit 127; }
fi
command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 127; }
command -v python3 >/dev/null 2>&1 || { echo "python3 is required" >&2; exit 127; }

if [[ -t 1 ]]; then
  BOLD=$'\033[1m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; CYAN=$'\033[36m'; RED=$'\033[31m'; OFF=$'\033[0m'
else
  BOLD=""; GREEN=""; YELLOW=""; CYAN=""; RED=""; OFF=""
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
EVIDENCE_DIR="reports/demo"
EVIDENCE_FILE="$EVIDENCE_DIR/demo-$STAMP.md"
mkdir -p "$EVIDENCE_DIR"

{
  echo "# 1-shot demo evidence — $STAMP"
  echo ""
  echo "Stack: US=$APP_US_URL EU=$APP_EU_URL router=$ROUTER_URL"
  echo ""
} > "$EVIDENCE_FILE"

banner() {
  echo ""
  echo "${BOLD}${CYAN}==> $1${OFF}"
  { echo ""; echo "## $1"; } >> "$EVIDENCE_FILE"
  if [[ "$PAUSE" == "true" ]]; then
    read -r -p "Press Enter to run this phase..." _
  fi
}

say()  { echo "  ${YELLOW}INFO${OFF} $1"; }
ok()   { echo "  ${GREEN}PASS${OFF} $1"; echo "- PASS: $1" >> "$EVIDENCE_FILE"; }
bad()  { echo "  ${RED}FAIL${OFF} $1"; echo "- FAIL: $1" >> "$EVIDENCE_FILE"; exit 1; }
note() { echo "$1" >> "$EVIDENCE_FILE"; }

json_get() { # $1=url $2=python-expr-on-d
  curl -sf --max-time 15 "$1" | python3 -c "import json,sys; d=json.load(sys.stdin); print($2)"
}

db_psql() { # $1=us|eu $2=sql
  local container="$US_DB_CONTAINER"
  [[ "$1" == "eu" ]] && container="$EU_DB_CONTAINER"
  docker exec "$container" psql -U "$DB_USER" -d "$DB_NAME" -Atqc "$2"
}

wait_ready() { # $1=url $2=label
  local deadline=$((SECONDS + TIMEOUT_SECONDS))
  while (( SECONDS < deadline )); do
    if curl -sf --max-time 3 "$1/actuator/health" >/dev/null 2>&1; then
      ok "$2 is ready ($1)"
      return 0
    fi
    sleep 2
  done
  bad "$2 did not become ready within ${TIMEOUT_SECONDS}s"
}

open_browser() {
  local url="$ROUTER_URL/demo.html"
  if command -v open >/dev/null 2>&1; then open "$url" 2>/dev/null || true
  elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$url" 2>/dev/null || true
  fi
}

cleanup() {
  if [[ "$CLEANUP_STACK" == "true" ]]; then
    say "Stopping stack (--cleanup)..."
    docker compose down >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# ---------- stack ----------
port_in_use() { (echo > /dev/tcp/127.0.0.1/$1) >/dev/null 2>&1; }

if [[ "$RESET_STACK" == "true" ]]; then
  banner "Phase 0 — Fresh stack"
  docker compose down -v
elif [[ "$START_STACK" == "true" ]]; then
  banner "Phase 0 — Start stack"
  docker compose down >/dev/null 2>&1 || true
fi

if [[ "$START_STACK" == "true" ]]; then
  if [[ -z "${APP_US_HOST_PORT:-}" ]] && port_in_use 8080; then
    APP_US_HOST_PORT=18080
    APP_US_URL="http://localhost:18080"
    export APP_US_HOST_PORT
    say "port 8080 is busy (another container) → app-us on 18080"
  fi
  docker compose up -d --build
fi

if [[ "$OPEN_BROWSER" == "true" ]]; then
  open_browser
fi

banner "Phase 1 — Baseline: both regions UP, writer = postgres-us"
wait_ready "$APP_US_URL" "app-us"
wait_ready "$APP_EU_URL" "app-eu"
US_WRITER="$(json_get "$APP_US_URL/health" "d['writerNode']")"
EU_WRITER="$(json_get "$APP_EU_URL/health" "d['writerNode']")"
if [[ "$US_WRITER" != "postgres-us" || "$EU_WRITER" != "postgres-us" ]]; then
  echo ""
  echo "  ${YELLOW}Stack is in post-demo state (writer=$EU_WRITER). Re-run with --reset for a fresh US-writer stack.${OFF}"
  echo "  ${YELLOW}Or open the live console to inspect current state: $ROUTER_URL/demo.html${OFF}"
  exit 3
fi
ok "global writer is postgres-us on both apps"
US_COUNT="$(json_get "$APP_US_URL/api/products" "len(d)")"
EU_COUNT="$(json_get "$APP_EU_URL/api/products" "len(d)")"
ok "home reads serve local rows (us=$US_COUNT, eu=$EU_COUNT)"
note ""
note "Dashboard: $ROUTER_URL/demo.html"

banner "Phase 2 — Writer/reader split: write via EU, read from both"
SPLIT_NAME="demo-split-$STAMP"
CREATED="$(curl -sf --max-time 15 -X POST "$APP_EU_URL/api/products" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"$SPLIT_NAME\",\"price\":11.11}")"
SPLIT_ID="$(echo "$CREATED" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")"
ok "POST via EU app created id=$SPLIT_ID (routed to global writer)"
US_READ="$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 "$APP_US_URL/api/products/$SPLIT_ID")"
EU_READ="$(curl -s -o /dev/null -w "%{http_code}" --max-time 15 "$APP_EU_URL/api/products/$SPLIT_ID")"
[[ "$US_READ" == "200" ]] || bad "expected US read 200, got $US_READ"
[[ "$EU_READ" == "404" ]] || bad "expected EU home-read 404, got $EU_READ"
ok "US read 200 (writer holds the row), EU read 404 (home region does not)"

banner "Phase 3 — Fencing: EU activation refused while US holds writer"
REFUSE_CODE="$(curl -s -o /tmp/demo-refuse.json -w "%{http_code}" --max-time 15 \
  -X POST "$APP_EU_URL/admin/failover-activate")"
[[ "$REFUSE_CODE" == "503" ]] || bad "expected 503 refusal, got $REFUSE_CODE"
REFUSE_ERR="$(python3 -c "import json; print(json.load(open('/tmp/demo-refuse.json'))['error'])" )"
ok "EU activation refused with 503: $REFUSE_ERR"
note "Refusal: $REFUSE_ERR"

banner "Phase 4 — Switchover: fence US, promote EU, writes follow"
T0=$SECONDS
db_psql us "SELECT pg_catalog.set_writer_mode(false);" >/dev/null
[[ "$(db_psql us "SELECT pg_catalog.aurora_is_writer();")" == "f" ]] || bad "US fence did not apply"
say "US fenced (aurora_is_writer=false)"
ACT_CODE="$(curl -s -o /tmp/demo-activate.json -w "%{http_code}" --max-time 15 \
  -X POST "$APP_EU_URL/admin/failover-activate")"
[[ "$ACT_CODE" == "200" ]] || bad "expected 200 activation, got $ACT_CODE"
ok "EU activation accepted (HTTP 200)"
deadline=$((SECONDS + TIMEOUT_SECONDS))
while (( SECONDS < deadline )); do
  EU_AFTER="$(curl -sf --max-time 5 "$APP_EU_URL/health" || true)"
  [[ -z "$EU_AFTER" ]] && { sleep 2; continue; }
  W="$(echo "$EU_AFTER" | python3 -c "import json,sys; print(json.load(sys.stdin)['writerNode'])")"
  A="$(echo "$EU_AFTER" | python3 -c "import json,sys; print(json.load(sys.stdin)['active'])")"
  if [[ "$W" == "postgres-eu" && "$A" == "True" ]]; then break; fi
  sleep 2
done
[[ "${W:-}" == "postgres-eu" && "${A:-}" == "True" ]] || bad "EU did not converge to writer postgres-eu"
T1=$SECONDS
RTO=$((T1 - T0))
ok "EU converged: writerNode=postgres-eu active=true (RTO ~ ${RTO}s fence-to-active)"
note "RTO fence-to-active: ~${RTO}s"
[[ "$(db_psql eu "SELECT pg_catalog.aurora_is_writer();")" == "t" ]] || bad "EU writer_mode not set"
say "Restarting old-primary app to prove it rejoins as a writer-router..."
docker restart "$APP_US_CONTAINER" >/dev/null
wait_ready "$APP_US_URL" "app-us (restarted)"
deadline=$((SECONDS + TIMEOUT_SECONDS))
while (( SECONDS < deadline )); do
  US_AFTER="$(curl -sf --max-time 5 "$APP_US_URL/health" || true)"
  [[ -z "$US_AFTER" ]] && { sleep 2; continue; }
  UW="$(echo "$US_AFTER" | python3 -c "import json,sys; print(json.load(sys.stdin)['writerNode'])")"
  if [[ "$UW" == "postgres-eu" ]]; then break; fi
  sleep 2
done
[[ "${UW:-}" == "postgres-eu" ]] || bad "app-us did not reconcile to writer postgres-eu"
ok "app-us rejoined: writerNode=postgres-eu (old primary now routes to EU)"
POST_NAME="demo-post-failover-$STAMP"
curl -sf --max-time 20 -X POST "$APP_US_URL/api/products" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"$POST_NAME\",\"price\":23.50}" >/dev/null
EU_HIT="$(db_psql eu "SELECT count(*) FROM products WHERE name = '$POST_NAME';")"
US_HIT="$(db_psql us "SELECT count(*) FROM products WHERE name = '$POST_NAME';")"
[[ "$EU_HIT" == "1" && "$US_HIT" == "0" ]] || bad "write landed wrong (eu=$EU_HIT us=$US_HIT)"
ok "write via US app landed in postgres-eu (eu=1, us=0) — writes follow the sun"

if [[ "$KILL_PHASE" == "true" ]]; then
  banner "Phase 5 — Kill old writer: EU keeps serving"
  docker stop "$US_DB_CONTAINER" >/dev/null
  say "stopped $US_DB_CONTAINER"
  sleep 3
  KILL_NAME="demo-kill-$STAMP"
  curl -sf --max-time 30 -X POST "$APP_EU_URL/api/products" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$KILL_NAME\",\"price\":7.77}" >/dev/null
  EU_HIT2="$(db_psql eu "SELECT count(*) FROM products WHERE name = '$KILL_NAME';")"
  [[ "$EU_HIT2" == "1" ]] || bad "EU write during US outage failed"
  ok "EU writes succeed while postgres-us is stopped"
  docker start "$US_DB_CONTAINER" >/dev/null
  say "restarted $US_DB_CONTAINER (reads recover; writer stays postgres-eu)"
  note "Kill phase: EU write during US outage OK; postgres-us restarted."
fi

banner "Demo complete"
echo ""
echo "  ${BOLD}Live console:${OFF}  $ROUTER_URL/demo.html"
echo "  ${BOLD}Evidence:${OFF}      $EVIDENCE_FILE"
echo "  ${BOLD}Writer now:${OFF}    postgres-eu (run with --reset for a fresh US-writer stack)"
echo ""
note ""
note "Final writer: postgres-eu"

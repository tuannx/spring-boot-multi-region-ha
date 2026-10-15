#!/usr/bin/env bash
# Failover drill: steady load through HAProxy, SIGKILL one bidder mid-run, then restart it.
#
# SIGKILL is a crash, not a graceful stop: no shutdown hook runs and in-flight requests are cut.
# Times in the drill are epoch milliseconds, so scripts/analyze.py can place the kill and the
# restart on the loadgen per-second timeline.
#
# Usage: cases/openrtb-ha/scripts/failover-drill.sh            (defaults below)
#        RPS=500 DURATION=90 KILL_AT=30 RESTART_AT=60 cases/openrtb-ha/scripts/failover-drill.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # cases/openrtb-ha/
REPO="$HERE"
RPS="${RPS:-300}"
DURATION="${DURATION:-60}"
WARMUP="${WARMUP:-10}"
KILL_AT="${KILL_AT:-20}"        # seconds after the start of the run (warm-up included)
RESTART_AT="${RESTART_AT:-40}"  # seconds after the start of the run
VICTIM="${VICTIM:-bidder-a}"
SETTLE="${SETTLE:-60}"          # seconds of quiet load before the run, so JIT warm-up is finished
TARGET="${TARGET:-http://localhost:8088}"
OUT="${OUT:-$REPO/docs/benchmarks/raw/failover-$(date -u +%Y%m%dT%H%M%SZ)}"
LOADGEN="$REPO/loadgen/build/libs/loadgen-0.1.0-SNAPSHOT.jar"
COMPOSE=(docker compose -f "$HERE/docker-compose.yml")

if (( KILL_AT >= RESTART_AT || RESTART_AT >= WARMUP + DURATION )); then
  echo "error: need KILL_AT < RESTART_AT < WARMUP+DURATION" >&2
  exit 2
fi

mkdir -p "$OUT"
"${COMPOSE[@]}" up -d >/dev/null

for _ in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
    -d '{"id":"probe","tmax":100,"imp":[{"id":"1","bidfloor":1,"banner":{}}]}' "$TARGET/bid" || true)
  [[ "$code" == "200" ]] && break
  sleep 1
done
[[ "$code" == "200" ]] || { echo "error: bid path through $TARGET is not answering" >&2; exit 1; }

# A bidder restarted by the previous drill is still JIT-compiling. Its CPU load would show up as
# "pre-kill" errors, so let the stack settle before measuring.
sleep "$SETTLE"

START_MS=$(date +%s%3N)
java -jar "$LOADGEN" --target="$TARGET" --rps="$RPS" --duration="$DURATION" \
  --warmup="$WARMUP" --format=proto --out="$OUT/report.json" &
LG_PID=$!

# Offsets are measured from our own start time. The loadgen's clock starts a little later, so
# the epoch timestamps below are the authoritative positions on the timeline.
sleep $((KILL_AT))
KILL_MS=$(date +%s%3N)
"${COMPOSE[@]}" kill -s SIGKILL "$VICTIM" >/dev/null
echo "killed $VICTIM at $KILL_MS"

sleep $((RESTART_AT - KILL_AT))
RESTART_MS=$(date +%s%3N)
"${COMPOSE[@]}" start "$VICTIM" >/dev/null
echo "restarted $VICTIM at $RESTART_MS"

wait "$LG_PID"

"${COMPOSE[@]}" exec -T postgres psql -U openrtb -d openrtb -v start_ms="$START_MS" -At \
  -f - < "$HERE/scripts/billing-check.sql" > "$OUT/billing-check.txt"

cat > "$OUT/drill.json" <<EOF
{
  "victim": "$VICTIM",
  "killMode": "SIGKILL",
  "rps": $RPS,
  "warmupSec": $WARMUP,
  "durationSec": $DURATION,
  "runStartEpochMs": $START_MS,
  "killEpochMs": $KILL_MS,
  "restartEpochMs": $RESTART_MS
}
EOF

echo "report:  $OUT/report.json"
echo "billing: $OUT/billing-check.txt"
echo "next:    python3 $REPO/scripts/analyze.py failover $OUT"

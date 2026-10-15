#!/usr/bin/env bash
# Benchmark matrix through the HAProxy load balancer. Runs must not overlap, so this script is
# sequential. Each run: 10 s warm-up (sent, not counted) + 30 s measured.
#
# Output: docs/benchmarks/raw/<label>-<UTC timestamp>/report.json
# Summary: python3 scripts/analyze.py table docs/benchmarks/raw/*/report.json
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # cases/openrtb-ha/
REPO="$HERE"
LOADGEN="$REPO/loadgen/build/libs/loadgen-0.1.0-SNAPSHOT.jar"
TARGET="${TARGET:-http://localhost:8088}"

BIDDER_PORTS=(8081 8082)   # bidder-a, bidder-b (published by docker-compose.yml)
ANALYZE="$REPO/scripts/analyze.py"

run() {
  local label=$1
  shift
  local out="$REPO/docs/benchmarks/raw/${label}-$(date -u +%Y%m%dT%H%M%SZ)"
  mkdir -p "$out"
  echo "== $label: $*"
  local before after
  before=$(python3 "$ANALYZE" snapshot "${BIDDER_PORTS[@]}")
  java -jar "$LOADGEN" --target="$TARGET" --warmup=10 --duration=30 --out="$out/report.json" "$@" \
    | grep -v JAVA_TOOL
  after=$(python3 "$ANALYZE" snapshot "${BIDDER_PORTS[@]}")
  printf '{"before": %s, "after": %s}\n' "$before" "$after" > "$out/server.json"
}

# 0. Stack warm-up. Bidders JIT-compile their request path over the first few thousand requests.
#    Runs that start right after a restart are not representative, so this run is discarded.
run stack-warmup --rps=300 --duration=60 --format=proto --seed=0

# A. Codec: protobuf vs JSON at identical load. The order alternates, so any drift over the
#    session hits both formats equally.
for seed in 1 2 3 4; do
  run codec-proto --rps=300 --format=proto --seed="$seed"
  run codec-json --rps=300 --format=json --seed="$seed"
done

# B. Connections: keep-alive vs a new connection per request, at a lower rate because the
#    cold path creates an HTTP client for every request.
for seed in 1 2; do
  run conn-warm --rps=100 --format=proto --cold=false --seed="$seed"
  run conn-cold --rps=100 --format=proto --cold=true --seed="$seed"
done

echo "== done"

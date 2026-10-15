#!/usr/bin/env bash
# One measured run that also records database CPU, for the before/after comparison of the
# billing aggregation. Same load shape as run-benchmarks.sh: 10 s warm-up + 30 s measured.
#
# Usage: cases/openrtb-ha/scripts/db-cpu-run.sh <label> [loadgen flags...]
# Output: docs/benchmarks/raw/<label>-<UTC timestamp>/{report.json,server.json}
#   server.json holds bidder CPU and request counters, and PostgreSQL container CPU (ns), before
#   and after the run.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"   # cases/openrtb-ha/
REPO="$HERE"
LOADGEN="$REPO/loadgen/build/libs/loadgen-0.1.0-SNAPSHOT.jar"
ANALYZE="$REPO/scripts/analyze.py"
TARGET="${TARGET:-http://localhost:8088}"
BIDDER_PORTS=(8081 8082)
LABEL=$1
shift

PG_CPU_FILE=/sys/fs/cgroup/cpuacct/cpuacct.usage
pg_cpu_ns() {
  docker compose -f "$HERE/docker-compose.yml" exec -T postgres cat "$PG_CPU_FILE" | tr -d '\r'
}

OUT="$REPO/docs/benchmarks/raw/${LABEL}-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$OUT"

before_bidders=$(python3 "$ANALYZE" snapshot "${BIDDER_PORTS[@]}")
before_pg=$(pg_cpu_ns)
java -jar "$LOADGEN" --target="$TARGET" --warmup=10 --duration=30 --out="$OUT/report.json" "$@" \
  | grep -v JAVA_TOOL
after_pg=$(pg_cpu_ns)
after_bidders=$(python3 "$ANALYZE" snapshot "${BIDDER_PORTS[@]}")

cat > "$OUT/server.json" <<EOF
{"before": $before_bidders, "after": $after_bidders,
 "postgresCpuNs": {"before": $before_pg, "after": $after_pg}}
EOF
echo "postgres CPU during run: $(( (after_pg - before_pg) / 1000000 )) ms"

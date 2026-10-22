#!/usr/bin/env bash
set -euo pipefail
unset CDPATH

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -f reports/stack-images/images.tar.gz ]]; then
  gzip -dc reports/stack-images/images.tar.gz | docker load
fi

BASELINE="reports/failover-drill/baseline/drill-report.json"
args=()
if [[ -f "$BASELINE" ]]; then
  args+=(--baseline "$BASELINE")
fi

export PYTHONPATH="${ROOT_DIR}/scripts${PYTHONPATH:+:$PYTHONPATH}"
exec python3 -m failover_drill "${args[@]}" "$@"

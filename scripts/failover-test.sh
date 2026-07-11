#!/usr/bin/env bash
set -euo pipefail
unset CDPATH

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"

cat <<'EOF'
scripts/failover-test.sh now delegates to the canonical fenced acceptance path.
It destroys the Docker test volumes on completion so a promoted-writer state
cannot leak into a later run.
EOF

exec "${ROOT_DIR}/scripts/e2e-acceptance.sh" \
  --start \
  --cleanup \
  --verify-failover

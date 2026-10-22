#!/usr/bin/env bash
set -euo pipefail
unset CDPATH

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMMENT="${ROOT_DIR}/reports/failover-drill/comment.md"

if [[ ! -s "$COMMENT" ]]; then
  echo "Failover drill did not render a comment."
  exit 0
fi

if [[ "${GITHUB_EVENT_NAME:-}" != "pull_request" ]]; then
  exit 0
fi

python3 - "$ROOT_DIR" "$COMMENT" <<'PY'
import sys
from pathlib import Path

root = Path(sys.argv[1])
comment_path = Path(sys.argv[2])
sys.path.insert(0, str(root / "scripts"))
from failover_drill.report import record_unavailable_architecture_diff

original = comment_path.read_text(encoding="utf-8")
updated, unavailable = record_unavailable_architecture_diff(original)
if updated != original:
    comment_path.write_text(updated, encoding="utf-8")
if unavailable:
    print("::warning::AE diff unavailable", flush=True)
PY

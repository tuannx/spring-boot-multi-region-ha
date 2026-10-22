#!/usr/bin/env bash
set -euo pipefail
unset CDPATH

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTION_DIR="${ROOT_DIR}/architecture-engineer/.github/actions/failover-comment-diff"
ACTION_FILE="${ACTION_DIR}/action.yml"
if [[ ! -f "$ACTION_FILE" ]]; then
  ACTION_FILE="${ACTION_DIR}/action.yaml"
fi
if [[ ! -f "$ACTION_FILE" ]]; then
  echo "Checked out architecture-engineer has no failover-comment-diff action."
  exit 0
fi

TRACE="${ARCHITECTURE_DIFF_TRACE:-reports/failover-drill/otlp-trace.json}"
REPORT="${ARCHITECTURE_DIFF_REPORT:-reports/failover-drill/drill-report.json}"
COMMENT="${ARCHITECTURE_DIFF_COMMENT:-reports/failover-drill/comment.md}"
SCRIPT="${ROOT_DIR}/reports/failover-drill/architecture-diff-cli.sh"

python3 - "$ROOT_DIR" "$ACTION_FILE" "$ACTION_DIR" "$TRACE" "$REPORT" "$COMMENT" "$SCRIPT" <<'PY'
import sys
from pathlib import Path

root = Path(sys.argv[1])
action_file = Path(sys.argv[2])
action_dir = Path(sys.argv[3])
trace, report, comment, script_path = sys.argv[4:]
sys.path.insert(0, str(root / "scripts"))
from failover_drill.architecture_diff import composite_shell

shell = composite_shell(
    action_file.read_text(encoding="utf-8"),
    trace=str(root / trace),
    report=str(root / report),
    comment=str(root / comment),
    action_path=str(action_dir),
)
if shell is None:
    print("failover-comment-diff is not a composite action; the unavailable note will cover the slot.")
    sys.exit(0)
destination = Path(script_path)
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(shell, encoding="utf-8")
print(destination)
PY

if [[ ! -s "$SCRIPT" ]]; then
  exit 0
fi
bash "$SCRIPT"

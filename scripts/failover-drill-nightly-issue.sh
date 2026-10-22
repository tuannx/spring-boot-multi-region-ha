#!/usr/bin/env bash
set -euo pipefail
unset CDPATH

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REPORT="${ROOT_DIR}/reports/failover-drill/drill-report.json"
if [[ ! -s "$REPORT" ]]; then
  echo "No drill report to attach to a tracking issue."
  exit 1
fi

REPO="${FAILOVER_DRILL_ISSUE_REPO:-${GITHUB_REPOSITORY:-}}"
if [[ -z "$REPO" ]]; then
  echo "GITHUB_REPOSITORY is unset; cannot open the tracking issue."
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "gh is not available; cannot open the tracking issue."
  exit 1
fi

issues_file="$(mktemp)"
plan_file="$(mktemp)"
cleanup() {
  rm -f "$issues_file" "$plan_file" "${plan_file}.body"
}
trap cleanup EXIT

passed="$(python3 -c 'import json,sys; report=json.load(open(sys.argv[1], encoding="utf-8")); gate=report.get("nightly_gate") or report.get("gate") or {}; print("yes" if gate.get("passed") else "no")' "$REPORT")"

if ! gh issue list --repo "$REPO" --state open --limit 20 \
  --search '"[DR] Nightly failover drill" in:title' \
  --json number,title,body >"$issues_file"; then
  if [[ "$passed" == "yes" ]]; then
    echo "Listing tracking issues failed after a passing drill; the drill result still stands."
    exit 0
  fi
  echo "Listing tracking issues failed."
  exit 1
fi

export PYTHONPATH="${ROOT_DIR}/scripts${PYTHONPATH:+:$PYTHONPATH}"
python3 -m failover_drill.nightly_issue --report "$REPORT" --issues "$issues_file" --plan "$plan_file"

set +e
python3 - "$plan_file" "$REPO" <<'PY'
import json
import subprocess
import sys

plan = json.loads(open(sys.argv[1], encoding="utf-8").read())
repo = sys.argv[2]
body_path = sys.argv[1] + ".body"
with open(body_path, "w", encoding="utf-8") as handle:
    handle.write(plan["body"])


def run(args: list[str]) -> None:
    completed = subprocess.run(args, check=False)
    if completed.returncode != 0:
        raise SystemExit(completed.returncode)


action = plan["action"]
number = plan.get("number")
if action == "create":
    run(["gh", "issue", "create", "--repo", repo, "--title", plan["title"], "--body-file", body_path])
elif action == "update":
    run(["gh", "issue", "edit", str(number), "--repo", repo, "--body-file", body_path])
elif action == "close":
    run(["gh", "issue", "comment", str(number), "--repo", repo, "--body-file", body_path])
    run(["gh", "issue", "close", str(number), "--repo", repo, "--reason", "completed"])
elif action != "none":
    raise SystemExit(f"unknown tracking issue action {action}")

for extra in plan["close_numbers"]:
    run([
        "gh", "issue", "close", str(extra),
        "--repo", repo,
        "--reason", "not planned",
        "--comment", "Closing duplicate nightly failover drill tracker. One issue tracks this drill.",
    ])
print(f"Tracking issue plan: {action}")
PY
status=$?
set -e
if [[ "$status" -ne 0 ]]; then
  passed="$(python3 -c 'import json,sys; report=json.load(open(sys.argv[1], encoding="utf-8")); gate=report.get("nightly_gate") or report.get("gate") or {}; print("yes" if gate.get("passed") else "no")' "$REPORT")"
  if [[ "$passed" == "yes" ]]; then
    echo "Tracking issue update failed after a passing drill; the drill result still stands."
    exit 0
  fi
  exit "$status"
fi

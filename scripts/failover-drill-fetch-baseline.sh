#!/usr/bin/env bash
set -euo pipefail
unset CDPATH

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${ROOT_DIR}/reports/failover-drill/baseline"
mkdir -p "$DEST"
rm -f "$DEST/drill-report.json"

if ! command -v gh >/dev/null 2>&1; then
  echo "gh is not available; continuing without a main baseline."
  exit 0
fi

REPO="${GITHUB_REPOSITORY:-}"
if [[ -z "$REPO" ]]; then
  echo "GITHUB_REPOSITORY is unset; continuing without a main baseline."
  exit 0
fi

HEAD_REPO="${FAILOVER_DRILL_HEAD_REPOSITORY:-}"
if [[ -n "$HEAD_REPO" && "$HEAD_REPO" != "$REPO" ]]; then
  echo "Fork pull request; the workflow token does not fetch main baseline artifacts."
  exit 0
fi

runs="$(gh run list --repo "$REPO" --workflow e2e.yml --branch main --limit 30 --json databaseId,headSha,conclusion 2>/dev/null || true)"
if [[ -z "$runs" || "$runs" == "[]" ]]; then
  echo "No main workflow runs were visible for a failover baseline."
  exit 0
fi

DEST="$DEST" GITHUB_REPOSITORY="$REPO" python3 - "$runs" <<'PY'
import json
import os
import subprocess
import sys

runs = json.loads(sys.argv[1])
repo = os.environ["GITHUB_REPOSITORY"]
dest = os.environ["DEST"]
for run in runs:
    run_id = str(run["databaseId"])
    listed = subprocess.run(
        [
            "gh", "api", f"repos/{repo}/actions/runs/{run_id}/artifacts",
            "--jq",
            '.artifacts[] | select(.name=="failover-drill-baseline" and .expired==false) | .id',
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if listed.returncode != 0 or not listed.stdout.strip():
        continue
    downloaded = subprocess.run(
        [
            "gh", "run", "download", run_id,
            "--repo", repo,
            "--name", "failover-drill-baseline",
            "--dir", dest,
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    report = os.path.join(dest, "drill-report.json")
    if downloaded.returncode == 0 and os.path.isfile(report):
        print(f"Using failover baseline from main run {run_id} ({run.get('headSha', '')}).")
        raise SystemExit(0)
print("No failover-drill-baseline artifact on recent main runs.")
PY

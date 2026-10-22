#!/usr/bin/env bash
set -euo pipefail
unset CDPATH

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMMENT="${ROOT_DIR}/reports/failover-drill/comment.md"
MARKER="<!-- failover-drill-report -->"

if [[ ! -s "$COMMENT" ]]; then
  echo "Failover drill did not render a comment."
  exit 0
fi

if [[ "${GITHUB_EVENT_NAME:-}" != "pull_request" ]]; then
  exit 0
fi

REPO="${GITHUB_REPOSITORY:-}"
PR_NUMBER="${FAILOVER_DRILL_PR_NUMBER:-}"
HEAD_REPO="${FAILOVER_DRILL_HEAD_REPOSITORY:-}"

if [[ -z "$REPO" || -z "$PR_NUMBER" ]]; then
  echo "Pull request coordinates are missing; skipping the sticky comment."
  exit 0
fi

if [[ -n "$HEAD_REPO" && "$HEAD_REPO" != "$REPO" ]]; then
  echo "Fork pull request: skipping the sticky comment. The drill report artifact holds the measurements."
  exit 0
fi

comments_file="$(mktemp)"
if ! gh api --paginate "repos/${REPO}/issues/${PR_NUMBER}/comments" \
    --jq ".[] | select(.body | contains(\"${MARKER}\")) | .id" >"$comments_file"; then
  rm -f "$comments_file"
  echo "Listing pull request comments failed."
  exit 1
fi
existing="$(head -n 1 "$comments_file")"
rm -f "$comments_file"

payload="$(python3 -c 'import json,sys; print(json.dumps({"body": open(sys.argv[1], encoding="utf-8").read()}))' "$COMMENT")"

if [[ -n "$existing" ]]; then
  printf '%s' "$payload" | gh api --method PATCH "repos/${REPO}/issues/comments/${existing}" --input -
else
  printf '%s' "$payload" | gh api --method POST "repos/${REPO}/issues/${PR_NUMBER}/comments" --input -
fi

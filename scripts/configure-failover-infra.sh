#!/usr/bin/env bash
set -euo pipefail
unset CDPATH

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
CLEANUP=true

usage() {
  cat <<'EOF'
Usage: scripts/configure-failover-infra.sh [--cleanup|--keep]

The Docker init SQL now provisions the failover control state and promotion
functions. This compatibility command builds the stack and runs the canonical
acceptance path, including a verified local EU promotion.

Options:
  --cleanup   Stop the stack and remove volumes after verification (default).
  --keep      Keep the verified stack and volumes running for investigation.
  -h, --help  Show this help.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --cleanup)
      CLEANUP=true
      shift
      ;;
    --keep)
      CLEANUP=false
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown option: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

args=(--start --verify-failover)
if [ "${CLEANUP}" = true ]; then
  args+=(--cleanup)
fi

exec "${ROOT_DIR}/scripts/e2e-acceptance.sh" "${args[@]}"

#!/usr/bin/env bash
# ==============================================================================
# ServiceTalk Locality Router Benchmark Launcher
# Delegates to cases/servicetalk-router/scripts/stress-test.sh
# ==============================================================================
set -euo pipefail
unset CDPATH

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
exec "${ROOT_DIR}/cases/servicetalk-router/scripts/stress-test.sh" "$@"

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ARCHIFY_CACHE_DIR="${TMPDIR:-/tmp}/archify"
ARCHIFY_BIN=""

if [[ -n "${ARCHIFY_DIR:-}" && -f "${ARCHIFY_DIR}/archify/bin/archify.mjs" ]]; then
  ARCHIFY_BIN="${ARCHIFY_DIR}/archify/bin/archify.mjs"
elif [[ -f "${ARCHIFY_CACHE_DIR}/archify/bin/archify.mjs" ]]; then
  ARCHIFY_BIN="${ARCHIFY_CACHE_DIR}/archify/bin/archify.mjs"
else
  echo "==> Fetching Archify repository to ${ARCHIFY_CACHE_DIR}..."
  rm -rf "${ARCHIFY_CACHE_DIR}"
  git clone --depth 1 https://github.com/tt-a1i/archify.git "${ARCHIFY_CACHE_DIR}"
  ARCHIFY_BIN="${ARCHIFY_CACHE_DIR}/archify/bin/archify.mjs"
fi

echo "==> Using Archify binary at: ${ARCHIFY_BIN}"

cd "${REPO_ROOT}"

echo "==> 1. Validating architecture specification (quality: showcase)..."
node "${ARCHIFY_BIN}" validate architecture docs/architecture.json --quality showcase --json

echo "==> 2. Delivering standalone interactive HTML diagram to docs/index.html..."
node "${ARCHIFY_BIN}" deliver architecture docs/architecture.json docs/index.html --quality showcase --json

echo "==> 3. Running headless browser visual check and generating screenshots..."
node "${ARCHIFY_BIN}" visual-check docs/index.html --json

mkdir -p docs/assets

# Copy high-resolution dark and light captures for README & docs
if [[ -f "docs/index.visual-check.1440x900.dark.png" ]]; then
  cp "docs/index.visual-check.1440x900.dark.png" "docs/assets/architecture-dark.png"
  echo "==> Generated docs/assets/architecture-dark.png"
fi

if [[ -f "docs/index.visual-check.1440x900.light.png" ]]; then
  cp "docs/index.visual-check.1440x900.light.png" "docs/assets/architecture-light.png"
  echo "==> Generated docs/assets/architecture-light.png"
fi

# Clean up visual-check temporary sidecars
rm -f docs/index.visual-check.*

echo "==> Archify architecture diagram generation completed successfully!"

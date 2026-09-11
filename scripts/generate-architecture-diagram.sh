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
mkdir -p docs/assets

# =========================================================
# CASE 1: Aurora / PostgreSQL Multi-Region HA
# =========================================================
echo "==> [Case 1] Validating Aurora architecture (showcase profile)..."
node "${ARCHIFY_BIN}" validate architecture docs/architecture.json --quality showcase --json

echo "==> [Case 1] Delivering HTML to docs/index.html..."
node "${ARCHIFY_BIN}" deliver architecture docs/architecture.json docs/index.html --quality showcase --json

echo "==> [Case 1] Running visual-check and capturing screenshots..."
node "${ARCHIFY_BIN}" visual-check docs/index.html --json

if [[ -f "docs/index.visual-check.1440x900.dark.png" ]]; then
  cp "docs/index.visual-check.1440x900.dark.png" "docs/assets/architecture-dark.png"
  echo "==> Generated docs/assets/architecture-dark.png"
fi

if [[ -f "docs/index.visual-check.1440x900.light.png" ]]; then
  cp "docs/index.visual-check.1440x900.light.png" "docs/assets/architecture-light.png"
  echo "==> Generated docs/assets/architecture-light.png"
fi
rm -f docs/index.visual-check.*

# =========================================================
# CASE 2: Cassandra Multi-Region Active-Active
# =========================================================
echo "==> [Case 2] Validating Cassandra architecture (showcase profile)..."
node "${ARCHIFY_BIN}" validate architecture docs/cassandra.json --quality showcase --json

echo "==> [Case 2] Delivering HTML to docs/cassandra.html..."
node "${ARCHIFY_BIN}" deliver architecture docs/cassandra.json docs/cassandra.html --quality showcase --json

echo "==> [Case 2] Running visual-check and capturing screenshots..."
node "${ARCHIFY_BIN}" visual-check docs/cassandra.html --json

if [[ -f "docs/cassandra.visual-check.1440x900.dark.png" ]]; then
  cp "docs/cassandra.visual-check.1440x900.dark.png" "docs/assets/cassandra-dark.png"
  echo "==> Generated docs/assets/cassandra-dark.png"
fi

if [[ -f "docs/cassandra.visual-check.1440x900.light.png" ]]; then
  cp "docs/cassandra.visual-check.1440x900.light.png" "docs/assets/cassandra-light.png"
  echo "==> Generated docs/assets/cassandra-light.png"
fi
rm -f docs/cassandra.visual-check.*

# =========================================================
# Case Switcher Navigation Dock for GitHub Pages
# =========================================================
inject_switcher() {
  local file="$1"
  local active_case="$2"
  local aur_bg="transparent"
  local cas_bg="transparent"
  local aur_border="transparent"
  local cas_border="transparent"

  if [[ "$active_case" == "aurora" ]]; then
    aur_bg="#2563eb"
    aur_border="rgba(255,255,255,0.2)"
  else
    cas_bg="#2563eb"
    cas_border="rgba(255,255,255,0.2)"
  fi

  local nav_html="<div id=\"archify-case-switcher\" style=\"position:fixed;bottom:20px;left:50%;transform:translateX(-50%);z-index:99999;display:flex;gap:6px;background:rgba(15,23,42,0.92);backdrop-filter:blur(10px);border:1px solid rgba(255,255,255,0.18);padding:5px 8px;border-radius:9999px;box-shadow:0 12px 30px rgba(0,0,0,0.6);font-family:system-ui,-apple-system,sans-serif;font-size:12.5px;font-weight:500;\"><a href=\"index.html\" style=\"padding:6px 14px;border-radius:9999px;text-decoration:none;color:#ffffff;transition:all .2s;background:${aur_bg};border:1px solid ${aur_border};display:flex;align-items:center;gap:6px;\"><span>📍</span> Case 1: Aurora HA (Single Writer)</a><a href=\"cassandra.html\" style=\"padding:6px 14px;border-radius:9999px;text-decoration:none;color:#ffffff;transition:all .2s;background:${cas_bg};border:1px solid ${cas_border};display:flex;align-items:center;gap:6px;\"><span>⚡</span> Case 2: Cassandra (Active-Active)</a></div>"

  if grep -q 'id="archify-case-switcher"' "$file"; then
    echo "==> Case switcher already present in $file"
  else
    sed -i '' "s|</body>|${nav_html}</body>|" "$file"
    echo "==> Injected case switcher into $file"
  fi
}

inject_switcher "docs/index.html" "aurora"
inject_switcher "docs/cassandra.html" "cassandra"

echo "==> All architecture diagrams generated and verified successfully!"

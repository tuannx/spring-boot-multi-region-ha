#!/usr/bin/env bash
set -euo pipefail

START_STACK=false
CLEANUP_STACK=false
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-180}"

usage() {
  cat <<USAGE
Usage: $0 [--start] [--cleanup]

Options:
  --start     Build and start the Docker Compose stack before testing.
  --cleanup   Stop the stack and remove volumes when the script exits.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --start)
      START_STACK=true
      shift
      ;;
    --cleanup)
      CLEANUP_STACK=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 127
  fi
}

find_python3() {
  local candidate resolved
  for candidate in "${PYTHON3:-}" python3 /opt/homebrew/bin/python3 /usr/local/bin/python3 /usr/bin/python3; do
    [[ -n "$candidate" ]] || continue
    resolved="$(command -v "$candidate" 2>/dev/null || true)"
    if [[ -n "$resolved" ]] && "$resolved" -c \
        'import sys; raise SystemExit(0 if sys.version_info >= (3, 8) else 1)' 2>/dev/null; then
      printf '%s\n' "$resolved"
      return 0
    fi
  done
  return 1
}

PYTHON_BIN="$(find_python3 || true)"
if [[ -z "$PYTHON_BIN" ]]; then
  echo "Python 3.8 or newer is required" >&2
  exit 127
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [[ "$START_STACK" == "true" || "$CLEANUP_STACK" == "true" ]]; then
  require_cmd docker
fi

cleanup() {
  local status=$?
  if [[ $status -ne 0 ]] && command -v docker >/dev/null 2>&1; then
    mkdir -p reports/queue-takeover
    docker compose logs --no-color > reports/queue-takeover/e2e-compose.log 2>&1 || true
  fi
  if [[ "$CLEANUP_STACK" == "true" ]]; then
    docker compose down --volumes --remove-orphans
  fi
  return "$status"
}
trap cleanup EXIT

if [[ "$START_STACK" == "true" ]]; then
  docker compose up -d --build
fi

"$PYTHON_BIN" - "$TIMEOUT_SECONDS" <<'PY'
import json
import sys
import time
import urllib.error
import urllib.request

timeout_seconds = float(sys.argv[1])
us_url = "http://localhost:8080"
eu_url = "http://localhost:8081"


def request(root, method, path, body=None, expected=(200,)):
    payload = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(root + path, data=payload, method=method)
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            raw = response.read().decode("utf-8")
            if response.status not in expected:
                raise AssertionError(f"{method} {root}{path}: expected {expected}, got {response.status}")
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as exc:
        if exc.code in expected:
            return exc.code, None
        raise


def wait_ready(root):
    deadline = time.time() + timeout_seconds
    last_error = None
    while time.time() < deadline:
        try:
            request(root, "GET", "/actuator/health")
            return
        except Exception as exc:
            last_error = exc
            time.sleep(1)
    raise SystemExit(f"{root} did not become ready within {timeout_seconds}s: {last_error}")


wait_ready(us_url)
wait_ready(eu_url)

_, us_products = request(us_url, "GET", "/api/products")
_, eu_products = request(eu_url, "GET", "/api/products")
assert us_products and all(item["region"] == "us-east-1" for item in us_products), us_products
assert eu_products and all(item["region"] == "eu-west-1" for item in eu_products), eu_products

product_name = f"e2e-writer-routing-{int(time.time())}"
_, created = request(eu_url, "POST", "/api/products", {
    "name": product_name,
    "price": 17.25
}, expected=(201,))
product_id = created["id"]
assert created["region"] == "eu-west-1", created

_, writer_read = request(us_url, "GET", f"/api/products/{product_id}")
assert writer_read["name"] == product_name, writer_read
request(eu_url, "GET", f"/api/products/{product_id}", expected=(404,))

request(us_url, "DELETE", f"/api/products/{product_id}", expected=(204,))
request(us_url, "GET", f"/api/products/{product_id}", expected=(404,))

print(json.dumps({
    "status": "PASS",
    "usReadPoolProducts": len(us_products),
    "euReadPoolProducts": len(eu_products),
    "writerRoutedProductId": product_id
}, indent=2))
PY

SIMULATE_BROTHER_APP_DOWN=true \
TIMEOUT_SECONDS="$TIMEOUT_SECONDS" \
./scripts/queue-takeover-acceptance.sh

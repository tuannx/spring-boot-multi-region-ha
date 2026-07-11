#!/usr/bin/env bash
set -euo pipefail
unset CDPATH

START_STACK=false
CLEANUP_STACK=false
VERIFY_FAILOVER=false
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-180}"

usage() {
  cat <<USAGE
Usage: $0 [--start] [--cleanup] [--verify-failover]

Options:
  --start     Build and start the Docker Compose stack before testing.
  --cleanup   Stop the stack and remove volumes when the script exits.
  --verify-failover
              Fence US, promote EU, verify write routing, then verify restart recovery.
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
    --verify-failover)
      VERIFY_FAILOVER=true
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

if [[ "$START_STACK" == "true" || "$CLEANUP_STACK" == "true" || "$VERIFY_FAILOVER" == "true" ]]; then
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

"$PYTHON_BIN" - "$TIMEOUT_SECONDS" "$VERIFY_FAILOVER" <<'PY'
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

timeout_seconds = float(sys.argv[1])
verify_failover = sys.argv[2].lower() == "true"
us_url = "http://localhost:8080"
eu_url = "http://localhost:8081"
router_url = "http://localhost:8000"


def request(root, method, path, body=None, expected=(200,), headers=None):
    payload = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(root + path, data=payload, method=method)
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    for name, value in (headers or {}).items():
        req.add_header(name, value)
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            raw = response.read().decode("utf-8")
            if response.status not in expected:
                raise AssertionError(f"{method} {root}{path}: expected {expected}, got {response.status}")
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as exc:
        if exc.code in expected:
            raw = exc.read().decode("utf-8")
            return exc.code, json.loads(raw) if raw else None
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


def docker_psql(container, sql):
    result = subprocess.run(
        [
            "docker", "exec", container,
            "psql", "-U", "appuser", "-d", "appdb", "-Atqc", sql,
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise AssertionError(
            f"psql failed in {container}: {result.stderr.strip()}"
        )
    return result.stdout.strip()


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

failover_activation_verified = False
failover_writer_routing_verified = False
failover_product_name = None
old_primary_rerouted = False
if verify_failover:
    refused_status, refused = request(
        eu_url,
        "POST",
        "/admin/failover-activate",
        expected=(503,),
    )
    assert refused_status == 503, refused_status
    assert refused["status"] == "activation_failed", refused
    assert "still reports writer postgres-us" in refused["error"], refused

    # Model the external control-plane fencing that real Aurora performs before
    # promoting a secondary. The local databases are independent, so this step
    # deliberately demotes US before the EU application is allowed to activate.
    docker_psql(
        "multiregion-us",
        "SELECT pg_catalog.set_writer_mode(false);",
    )
    assert docker_psql(
        "multiregion-us",
        "SELECT pg_catalog.aurora_is_writer();",
    ) == "f"

    _, activation = request(
        eu_url,
        "POST",
        "/admin/failover-activate",
        expected=(200,),
    )
    assert activation["status"] == "activated", activation
    _, eu_health = request(eu_url, "GET", "/health")
    assert eu_health["active"] is True, eu_health

    assert docker_psql(
        "multiregion-eu",
        "SELECT pg_catalog.aurora_is_writer();",
    ) == "t"

    failover_product_name = f"e2e-post-failover-{int(time.time())}"
    _, failover_product = request(eu_url, "POST", "/api/products", {
        "name": failover_product_name,
        "price": 23.50,
    }, expected=(201,))
    assert failover_product["region"] == "eu-west-1", failover_product

    _, eu_after_failover = request(eu_url, "GET", "/api/products")
    _, us_after_failover = request(us_url, "GET", "/api/products")
    assert any(
        item["name"] == failover_product_name
        for item in eu_after_failover
    ), eu_after_failover
    assert all(
        item["name"] != failover_product_name
        for item in us_after_failover
    ), us_after_failover
    assert docker_psql(
        "multiregion-eu",
        f"SELECT count(*) FROM products WHERE name = '{failover_product_name}';",
    ) == "1"
    assert docker_psql(
        "multiregion-us",
        f"SELECT count(*) FROM products WHERE name = '{failover_product_name}';",
    ) == "0"

    # Restart the old-primary compute process to prove persisted demotion is
    # reconciled and its writes follow the new global writer instead of the
    # fenced US database.
    subprocess.run(
        ["docker", "restart", "multiregion-app-us"],
        check=True,
        stdout=subprocess.DEVNULL,
    )
    wait_ready(us_url)
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        _, us_health = request(us_url, "GET", "/health")
        if us_health["active"] is True and us_health["writerNode"] == "postgres-eu":
            break
        time.sleep(1)
    else:
        raise AssertionError(f"old primary did not reconcile promoted route: {us_health}")

    old_primary_product_name = f"e2e-old-primary-rerouted-{int(time.time())}"
    _, old_primary_product = request(us_url, "POST", "/api/products", {
        "name": old_primary_product_name,
        "price": 19.00,
    }, expected=(201,))
    assert old_primary_product["region"] == "us-east-1", old_primary_product
    _, eu_after_old_primary_write = request(eu_url, "GET", "/api/products")
    _, us_after_old_primary_write = request(us_url, "GET", "/api/products")
    assert any(
        item["name"] == old_primary_product_name
        for item in eu_after_old_primary_write
    ), eu_after_old_primary_write
    assert all(
        item["name"] != old_primary_product_name
        for item in us_after_old_primary_write
    ), us_after_old_primary_write
    assert docker_psql(
        "multiregion-eu",
        f"SELECT count(*) FROM products WHERE name = '{old_primary_product_name}';",
    ) == "1"
    assert docker_psql(
        "multiregion-us",
        f"SELECT count(*) FROM products WHERE name = '{old_primary_product_name}';",
    ) == "0"

    router_product_name = f"e2e-router-after-failover-{int(time.time())}"
    _, router_product = request(router_url, "POST", "/api/products", {
        "name": router_product_name,
        "price": 27.00,
    }, expected=(201,), headers={"X-Source-Region": "eu-west-1"})
    assert router_product["region"] == "eu-west-1", router_product

    failover_activation_verified = True
    failover_writer_routing_verified = True
    old_primary_rerouted = True

print(json.dumps({
    "status": "PASS",
    "usReadPoolProducts": len(us_products),
    "euReadPoolProducts": len(eu_products),
    "writerRoutedProductId": product_id,
    "failoverActivationVerified": failover_activation_verified,
    "failoverWriterRoutingVerified": failover_writer_routing_verified,
    "failoverProductName": failover_product_name,
    "oldPrimaryRerouted": old_primary_rerouted,
}, indent=2))
PY

if [[ "$VERIFY_FAILOVER" == "true" ]]; then
  PRIMARY_WRITER="$(docker exec multiregion-us \
    psql -U appuser -d appdb -Atqc "SELECT pg_catalog.aurora_is_writer();")"
  LOCAL_WRITER="$(docker exec multiregion-eu \
    psql -U appuser -d appdb -Atqc "SELECT pg_catalog.aurora_is_writer();")"
  if [[ "$PRIMARY_WRITER" != "f" ]]; then
    echo "postgres-us was not fenced before EU promotion" >&2
    exit 1
  fi
  if [[ "$LOCAL_WRITER" != "t" ]]; then
    echo "EU promotion endpoint returned success but postgres-eu is not writer" >&2
    exit 1
  fi
  echo "Verified single-writer control state and post-promotion EU write routing."
fi

SIMULATE_BROTHER_APP_DOWN=true \
TIMEOUT_SECONDS="$TIMEOUT_SECONDS" \
./scripts/queue-takeover-acceptance.sh

if [[ "$VERIFY_FAILOVER" == "true" ]]; then
  "$PYTHON_BIN" - "$TIMEOUT_SECONDS" <<'PY'
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

timeout_seconds = float(sys.argv[1])
eu_url = "http://localhost:8081"
us_url = "http://localhost:8080"


def request(root, method, path, body=None, expected=(200,)):
    payload = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(root + path, data=payload, method=method)
    if payload is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            raw = response.read().decode("utf-8")
            if response.status not in expected:
                raise AssertionError(
                    f"{method} {root}{path}: expected {expected}, got {response.status}"
                )
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as exc:
        if exc.code in expected:
            return None
        raise


deadline = time.time() + timeout_seconds
last_error = None
while time.time() < deadline:
    try:
        health = request(eu_url, "GET", "/health")
        if health["active"] is True:
            break
        last_error = AssertionError(f"EU restarted inactive: {health}")
    except Exception as exc:
        last_error = exc
    time.sleep(1)
else:
    raise SystemExit(
        f"EU did not reconcile persisted promotion within {timeout_seconds}s: {last_error}"
    )

name = f"e2e-post-restart-failover-{int(time.time())}"
created = request(eu_url, "POST", "/api/products", {
    "name": name,
    "price": 31.75,
}, expected=(201,))
assert created["region"] == "eu-west-1", created

eu_products = request(eu_url, "GET", "/api/products")
us_products = request(us_url, "GET", "/api/products")
assert any(item["name"] == name for item in eu_products), eu_products
assert all(item["name"] != name for item in us_products), us_products

writer_state = subprocess.run(
    [
        "docker", "exec", "multiregion-eu",
        "psql", "-U", "appuser", "-d", "appdb", "-Atqc",
        "SELECT pg_catalog.aurora_is_writer();",
    ],
    capture_output=True,
    text=True,
    check=True,
).stdout.strip()
assert writer_state == "t", writer_state

print(json.dumps({
    "status": "PASS",
    "restartReconciliationVerified": True,
    "postRestartWriterProduct": name,
}, indent=2))
PY
fi

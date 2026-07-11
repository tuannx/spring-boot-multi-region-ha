#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
BROTHER_BASE_URL="${BROTHER_BASE_URL:-http://localhost:8081}"
QUEUE_NAME="${QUEUE_NAME:-orders}"
LOCAL_REGION="${LOCAL_REGION:-us-east-1}"
BROTHER_REGION="${BROTHER_REGION:-eu-west-1}"
SIMULATE_BROTHER_APP_DOWN="${SIMULATE_BROTHER_APP_DOWN:-false}"
BROTHER_APP_CONTAINER="${BROTHER_APP_CONTAINER:-multiregion-app-eu}"
TIMEOUT_SECONDS="${TIMEOUT_SECONDS:-20}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-0.25}"
REPORT_DIR="${REPORT_DIR:-reports/queue-takeover}"
START_STACK=false

usage() {
  cat <<USAGE
Usage: $0 [--start]

Environment overrides:
  BASE_URL=${BASE_URL}
  BROTHER_BASE_URL=${BROTHER_BASE_URL}
  QUEUE_NAME=${QUEUE_NAME}
  LOCAL_REGION=${LOCAL_REGION}
  BROTHER_REGION=${BROTHER_REGION}
  SIMULATE_BROTHER_APP_DOWN=${SIMULATE_BROTHER_APP_DOWN}
  BROTHER_APP_CONTAINER=${BROTHER_APP_CONTAINER}
  TIMEOUT_SECONDS=${TIMEOUT_SECONDS}
  POLL_INTERVAL_SECONDS=${POLL_INTERVAL_SECONDS}
  REPORT_DIR=${REPORT_DIR}

Options:
  --start   Run docker compose up -d --build before the acceptance test.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --start)
      START_STACK=true
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

if [[ "$START_STACK" == "true" ]]; then
  require_cmd docker
  docker compose up -d --build
fi

mkdir -p "$REPORT_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
REPORT_JSON="$REPORT_DIR/queue-takeover-$STAMP.json"
REPORT_MD="$REPORT_DIR/queue-takeover-$STAMP.md"

"$PYTHON_BIN" - "$BASE_URL" "$BROTHER_BASE_URL" "$QUEUE_NAME" "$LOCAL_REGION" "$BROTHER_REGION" "$SIMULATE_BROTHER_APP_DOWN" "$BROTHER_APP_CONTAINER" "$TIMEOUT_SECONDS" "$POLL_INTERVAL_SECONDS" "$REPORT_JSON" "$REPORT_MD" <<'PY'
import json
import http.client
import subprocess
import sys
import time
import base64
import atexit
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

base_url, brother_base_url, queue_name, local_region, brother_region = sys.argv[1:6]
simulate_brother_app_down = sys.argv[6].lower() == "true"
brother_app_container = sys.argv[7]
timeout_seconds = float(sys.argv[8])
poll_interval_seconds = float(sys.argv[9])
report_json = sys.argv[10]
report_md = sys.argv[11]

events = []
brother_app_stopped = False

def now_iso():
    return datetime.now(timezone.utc).isoformat()

def now_ms():
    return int(time.time() * 1000)

def request_at(root_url, method, path, required=True):
    url = root_url.rstrip("/") + path
    req = urllib.request.Request(url, method=method)
    started = now_ms()
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            raw = response.read().decode("utf-8")
            duration_ms = now_ms() - started
            data = json.loads(raw) if raw else {}
            events.append({
                "at": now_iso(),
                "method": method,
                "path": path,
                "status": response.status,
                "durationMs": duration_ms
            })
            return data
    except (urllib.error.URLError, ConnectionError, http.client.RemoteDisconnected, TimeoutError) as exc:
        if not required:
            events.append({
                "at": now_iso(),
                "method": method,
                "path": path,
                "rootUrl": root_url,
                "status": "skipped_unreachable",
                "error": str(exc)
            })
            return {}
        raise SystemExit(f"Cannot reach {url}: {exc}") from exc

def request(method, path):
    return request_at(base_url, method, path)

def wait_ready():
    deadline = time.time() + timeout_seconds
    last_error = None
    while time.time() < deadline:
        try:
            return queue_state()
        except SystemExit as exc:
            last_error = exc
            time.sleep(poll_interval_seconds)
    raise SystemExit(f"Service did not become ready within {timeout_seconds}s: {last_error}")

def queue_state():
    return request("GET", "/admin/queues")

def mark(region, status, reason):
    encoded_reason = urllib.parse.quote(reason)
    return request("POST", f"/admin/queues/{queue_name}/{region}/{status}?reason={encoded_reason}")

def mark_everywhere(region, status, reason):
    encoded_reason = urllib.parse.quote(reason)
    path = f"/admin/queues/{queue_name}/{region}/{status}?reason={encoded_reason}"
    response = request_at(base_url, "POST", path)
    if brother_base_url:
        request_at(brother_base_url, "POST", path, required=False)
    return response

def assignments(snapshot):
    return snapshot.get("runningAssignments", [])

def has_assignment(snapshot, owner_region, mode):
    return any(
        item.get("queueName") == queue_name
        and item.get("ownerRegion") == owner_region
        and item.get("mode") == mode
        for item in assignments(snapshot)
    )

def wait_until(label, predicate):
    deadline = time.time() + timeout_seconds
    samples = []
    started = now_ms()
    while time.time() < deadline:
        snapshot = queue_state()
        samples.append({
            "at": now_iso(),
            "assignments": assignments(snapshot)
        })
        if predicate(snapshot):
            return {
                "label": label,
                "ok": True,
                "delayMs": now_ms() - started,
                "samples": samples,
                "finalSnapshot": snapshot
            }
        time.sleep(poll_interval_seconds)

    return {
        "label": label,
        "ok": False,
        "delayMs": now_ms() - started,
        "samples": samples,
        "finalSnapshot": samples[-1] if samples else {}
    }

def publish_rabbitmq_message(region, payload):
    management_port = 15672 if region == "us-east-1" else 15673
    publish_url = f"http://localhost:{management_port}/api/exchanges/%2F/amq.default/publish"
    physical_queue = f"{queue_name}.{region}"
    body = json.dumps({
        "properties": {},
        "routing_key": physical_queue,
        "payload": payload,
        "payload_encoding": "string"
    }).encode("utf-8")
    req = urllib.request.Request(publish_url, data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Basic " + base64.b64encode(b"appuser:apppass").decode("ascii"))
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            events.append({
                "at": now_iso(),
                "method": "POST",
                "path": publish_url,
                "status": response.status,
                "queue": physical_queue,
                "payload": payload
            })
    except Exception as exc:
        events.append({
            "at": now_iso(),
            "method": "POST",
            "path": publish_url,
            "status": "publish_failed",
            "queue": physical_queue,
            "error": str(exc)
        })

def docker_available():
    return subprocess.run(
        ["docker", "version"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False
    ).returncode == 0

def docker_container_exists(container_name):
    result = subprocess.run(
        ["docker", "inspect", container_name],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False
    )
    return result.returncode == 0

def docker_container_running(container_name):
    result = subprocess.run(
        ["docker", "inspect", "-f", "{{.State.Running}}", container_name],
        capture_output=True,
        text=True,
        check=False
    )
    return result.returncode == 0 and result.stdout.strip() == "true"

def stop_brother_app_if_possible():
    if not simulate_brother_app_down:
        return False
    if not docker_available() or not docker_container_exists(brother_app_container):
        events.append({
            "at": now_iso(),
            "method": "docker",
            "path": brother_app_container,
            "status": "skip_stop_brother_app"
        })
        return False
    if not docker_container_running(brother_app_container):
        return False
    subprocess.run(["docker", "stop", brother_app_container], check=True)
    events.append({
        "at": now_iso(),
        "method": "docker stop",
        "path": brother_app_container,
        "status": 0
    })
    return True

def start_brother_app_if_stopped(stopped):
    global brother_app_stopped
    if not stopped:
        return
    subprocess.run(["docker", "start", brother_app_container], check=True)
    brother_app_stopped = False
    events.append({
        "at": now_iso(),
        "method": "docker start",
        "path": brother_app_container,
        "status": 0
    })

def cleanup_brother_app():
    if brother_app_stopped:
        subprocess.run(["docker", "start", brother_app_container], check=False)

atexit.register(cleanup_brother_app)

baseline_started = now_ms()
wait_ready()
mark_everywhere(local_region, "up", "acceptance-reset-local")
mark_everywhere(brother_region, "up", "acceptance-reset-brother")
baseline = wait_until(
    "baseline_primary_only",
    lambda snapshot: has_assignment(snapshot, local_region, "PRIMARY")
    and not has_assignment(snapshot, brother_region, "TAKEOVER")
)
publish_rabbitmq_message(local_region, f"acceptance-local-{int(time.time())}")
time.sleep(1)

takeover_start_ms = now_ms()
brother_app_stopped = stop_brother_app_if_possible()
mark_everywhere(brother_region, "down", "acceptance-brother-down")
takeover = wait_until(
    "brother_down_takeover",
    lambda snapshot: has_assignment(snapshot, local_region, "PRIMARY")
    and has_assignment(snapshot, brother_region, "TAKEOVER")
)
takeover_payload = f"acceptance-takeover-{int(time.time())}"
publish_rabbitmq_message(brother_region, takeover_payload)
time.sleep(1)

release_start_ms = now_ms()
mark_everywhere(brother_region, "up", "acceptance-brother-recovered")
start_brother_app_if_stopped(brother_app_stopped)
release = wait_until(
    "brother_up_release",
    lambda snapshot: has_assignment(snapshot, local_region, "PRIMARY")
    and not has_assignment(snapshot, brother_region, "TAKEOVER")
)

result = {
    "startedAt": now_iso(),
    "baseUrl": base_url,
    "brotherBaseUrl": brother_base_url,
    "queueName": queue_name,
    "localRegion": local_region,
    "brotherRegion": brother_region,
    "simulateBrotherAppDown": simulate_brother_app_down,
    "brotherAppContainer": brother_app_container,
    "takeoverPayload": takeover_payload,
    "timeoutSeconds": timeout_seconds,
    "pollIntervalSeconds": poll_interval_seconds,
    "baseline": baseline,
    "takeover": takeover,
    "release": release,
    "events": events,
    "summary": {
        "ok": baseline["ok"] and takeover["ok"] and release["ok"],
        "baselineDelayMs": baseline["delayMs"],
        "takeoverDelayMs": takeover["delayMs"],
        "releaseDelayMs": release["delayMs"],
        "totalMeasuredMs": now_ms() - baseline_started,
        "takeoverMeasuredFromPostMs": now_ms() - takeover_start_ms,
        "releaseMeasuredFromPostMs": now_ms() - release_start_ms
    }
}

with open(report_json, "w", encoding="utf-8") as f:
    json.dump(result, f, indent=2)
    f.write("\n")

with open(report_md, "w", encoding="utf-8") as f:
    f.write("# Queue Takeover Acceptance Report\n\n")
    f.write(f"- Base URL: `{base_url}`\n")
    f.write(f"- Brother base URL: `{brother_base_url}`\n")
    f.write(f"- Queue: `{queue_name}`\n")
    f.write(f"- Local region: `{local_region}`\n")
    f.write(f"- Brother region: `{brother_region}`\n")
    f.write(f"- Simulate brother app down: `{simulate_brother_app_down}`\n")
    f.write(f"- Brother app container: `{brother_app_container}`\n")
    f.write(f"- Takeover payload: `{takeover_payload}`\n")
    f.write(f"- Poll interval: `{poll_interval_seconds}s`\n")
    f.write(f"- Timeout: `{timeout_seconds}s`\n\n")
    f.write("## Result\n\n")
    f.write(f"- Overall: `{'PASS' if result['summary']['ok'] else 'FAIL'}`\n")
    f.write(f"- Baseline delay: `{baseline['delayMs']}ms`\n")
    f.write(f"- Takeover delay: `{takeover['delayMs']}ms`\n")
    f.write(f"- Release delay: `{release['delayMs']}ms`\n\n")
    f.write("## Final Assignments\n\n")
    f.write("```json\n")
    json.dump(release.get("finalSnapshot", {}), f, indent=2)
    f.write("\n```\n")

print(json.dumps(result["summary"], indent=2))
print(f"JSON report: {report_json}")
print(f"Markdown report: {report_md}")

if not result["summary"]["ok"]:
    sys.exit(1)
PY

if command -v docker >/dev/null 2>&1; then
  LOG_FILE="$REPORT_DIR/queue-takeover-$STAMP-app-us.log"
  docker logs multiregion-app-us --since 10m > "$LOG_FILE" 2>&1 || true
  echo "App log snapshot: $LOG_FILE"

  RABBIT_US_LOG_FILE="$REPORT_DIR/queue-takeover-$STAMP-rabbitmq-us.log"
  RABBIT_EU_LOG_FILE="$REPORT_DIR/queue-takeover-$STAMP-rabbitmq-eu.log"
  docker logs multiregion-rabbitmq-us --since 10m > "$RABBIT_US_LOG_FILE" 2>&1 || true
  docker logs multiregion-rabbitmq-eu --since 10m > "$RABBIT_EU_LOG_FILE" 2>&1 || true
  echo "RabbitMQ US log snapshot: $RABBIT_US_LOG_FILE"
  echo "RabbitMQ EU log snapshot: $RABBIT_EU_LOG_FILE"
fi

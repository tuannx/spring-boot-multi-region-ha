#!/usr/bin/env bash
set -euo pipefail
unset CDPATH

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
SOURCE_PATH="app/src/main/java"
LANGUAGE="java"
REPO_NAME="spring-boot-multi-region-ha"
ALGORITHM="pkg"
PINNED_ARCADE_AGENT_VERSION="0.1.1"
VENV_DIR="${ARCADE_AGENT_VENV:-${ROOT_DIR}/.arcade/mcp-venv}"
PYTHON_BIN="${VENV_DIR}/bin/python"
SELF_ANALYSIS_BIN="${VENV_DIR}/bin/arcade-self-analysis"
COMPARE_BASELINE_BIN="${VENV_DIR}/bin/arcade-compare-baseline"
DEFAULT_BASELINE="${ROOT_DIR}/.arcade/baselines/main-full.json"

usage() {
  cat <<'EOF'
Usage:
  scripts/architecture-audit.sh --output-json PATH [options]

Create a full Arcade Agent self-analysis snapshot with the repository's fixed
analysis inputs: app/src/main/java, Java, and the pkg recovery algorithm.

Required:
  -o, --output-json PATH      Write the full current snapshot to PATH.

Analysis output:
      --output-html PATH      Override the self-analysis HTML path. By default,
                              write <output-json-name>.html beside the JSON.

Baseline comparison:
      --compare               Compare with .arcade/baselines/main-full.json.
      --baseline PATH         Compare with another full self-analysis JSON.
                              This implies --compare.
      --comparison-md PATH    Write the Markdown comparison to PATH. This
                              implies --compare. By default it is written next
                              to --output-json as <name>.comparison.md.
      --comparison-html PATH  Also write an HTML comparison. This implies
                              --compare.

Other:
  -h, --help                  Show this help.

Examples:
  scripts/architecture-audit.sh \
    --output-json /tmp/architecture-audit/current.json

  scripts/architecture-audit.sh \
    --output-json /tmp/architecture-audit/current.json \
    --output-html /tmp/architecture-audit/current.html \
    --compare \
    --comparison-md /tmp/architecture-audit/comparison.md \
    --comparison-html /tmp/architecture-audit/comparison.html
EOF
}

fail() {
  printf 'architecture-audit: %s\n' "$*" >&2
  exit 2
}

require_value() {
  local option="$1"
  local value="${2:-}"

  case "${value}" in
    ""|-*) fail "${option} requires a path" ;;
  esac
}

absolute_path() {
  local path="$1"

  case "${path}" in
    /*) printf '%s\n' "${path}" ;;
    *) printf '%s/%s\n' "${ROOT_DIR}" "${path}" ;;
  esac
}

prepare_output_path() {
  local path
  local directory
  local filename

  path="$(absolute_path "$1")"
  directory="$(dirname "${path}")"
  filename="$(basename "${path}")"
  mkdir -p "${directory}"
  directory="$(cd "${directory}" && pwd -P)"
  printf '%s/%s\n' "${directory}" "${filename}"
}

resolve_input_path() {
  local path
  local directory
  local filename

  path="$(absolute_path "$1")"
  [ -f "${path}" ] || fail "baseline does not exist: ${path}"
  directory="$(cd "$(dirname "${path}")" && pwd -P)"
  filename="$(basename "${path}")"
  printf '%s/%s\n' "${directory}" "${filename}"
}

validate_baseline_metadata() {
  local baseline="$1"

  "${PYTHON_BIN}" -c '
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
data = json.loads(path.read_text(encoding="utf-8"))
expected = {
    "analysis_source_path": sys.argv[2],
    "arcade_agent_version": sys.argv[3],
    "language": sys.argv[4],
    "algorithm": sys.argv[5],
    "repo_name": sys.argv[6],
}
mismatches = [
    f"{field}={data.get(field)!r} (expected {value!r})"
    for field, value in expected.items()
    if data.get(field) != value
]
if mismatches:
    raise SystemExit(
        "baseline metadata mismatch: " + "; ".join(mismatches)
    )
' "${baseline}" "${SOURCE_PATH}" "${PINNED_ARCADE_AGENT_VERSION}" \
    "${LANGUAGE}" "${ALGORITHM}" "${REPO_NAME}"
}

same_file() {
  local left="$1"
  local right="$2"

  [ "${left}" = "${right}" ] || \
    { [ -e "${left}" ] && [ -e "${right}" ] && [ "${left}" -ef "${right}" ]; }
}

OUTPUT_JSON=""
OUTPUT_HTML=""
COMPARE=false
BASELINE_PATH="${DEFAULT_BASELINE}"
COMPARISON_MD=""
COMPARISON_HTML=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    -o|--output-json)
      require_value "$1" "${2:-}"
      OUTPUT_JSON="$2"
      shift 2
      ;;
    --output-html)
      require_value "$1" "${2:-}"
      OUTPUT_HTML="$2"
      shift 2
      ;;
    --compare)
      COMPARE=true
      shift
      ;;
    --baseline)
      require_value "$1" "${2:-}"
      BASELINE_PATH="$2"
      COMPARE=true
      shift 2
      ;;
    --comparison-md)
      require_value "$1" "${2:-}"
      COMPARISON_MD="$2"
      COMPARE=true
      shift 2
      ;;
    --comparison-html)
      require_value "$1" "${2:-}"
      COMPARISON_HTML="$2"
      COMPARE=true
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown option: $1 (use --help)"
      ;;
  esac
done

[ -n "${OUTPUT_JSON}" ] || fail "--output-json is required (use --help)"

if [ ! -x "${SELF_ANALYSIS_BIN}" ] || [ ! -x "${COMPARE_BASELINE_BIN}" ]; then
  printf 'Bootstrapping the pinned Arcade Agent environment...\n' >&2
  "${ROOT_DIR}/scripts/arcade-mcp.sh" --help >/dev/null
fi

[ -x "${PYTHON_BIN}" ] || fail "missing executable: ${PYTHON_BIN}"
[ -x "${SELF_ANALYSIS_BIN}" ] || fail "missing executable: ${SELF_ANALYSIS_BIN}"
[ -x "${COMPARE_BASELINE_BIN}" ] || fail "missing executable: ${COMPARE_BASELINE_BIN}"

INSTALLED_ARCADE_AGENT_VERSION="$("${PYTHON_BIN}" -c \
  'from importlib.metadata import version; print(version("arcade-agent"))')"
if [ "${INSTALLED_ARCADE_AGENT_VERSION}" != "${PINNED_ARCADE_AGENT_VERSION}" ]; then
  fail "arcade-agent ${PINNED_ARCADE_AGENT_VERSION} is required; found ${INSTALLED_ARCADE_AGENT_VERSION}"
fi

OUTPUT_JSON="$(prepare_output_path "${OUTPUT_JSON}")"
if [ -z "${OUTPUT_HTML}" ]; then
  case "${OUTPUT_JSON}" in
    *.json) OUTPUT_HTML="${OUTPUT_JSON%.json}.html" ;;
    *) OUTPUT_HTML="${OUTPUT_JSON}.html" ;;
  esac
fi
OUTPUT_HTML="$(prepare_output_path "${OUTPUT_HTML}")"

if [ "${COMPARE}" = true ]; then
  BASELINE_PATH="$(resolve_input_path "${BASELINE_PATH}")"
  validate_baseline_metadata "${BASELINE_PATH}" || \
    fail "comparison baseline was not generated with the canonical inputs"

  if [ -z "${COMPARISON_MD}" ]; then
    case "${OUTPUT_JSON}" in
      *.json) COMPARISON_MD="${OUTPUT_JSON%.json}.comparison.md" ;;
      *) COMPARISON_MD="${OUTPUT_JSON}.comparison.md" ;;
    esac
  fi
  COMPARISON_MD="$(prepare_output_path "${COMPARISON_MD}")"

  if [ -n "${COMPARISON_HTML}" ]; then
    COMPARISON_HTML="$(prepare_output_path "${COMPARISON_HTML}")"
  fi
fi

CANONICAL_BASELINE="$(resolve_input_path "${DEFAULT_BASELINE}")"
OUTPUT_PATHS=("${OUTPUT_JSON}")
OUTPUT_PATHS+=("${OUTPUT_HTML}")
if [ "${COMPARE}" = true ]; then
  OUTPUT_PATHS+=("${COMPARISON_MD}")
  if [ -n "${COMPARISON_HTML}" ]; then
    OUTPUT_PATHS+=("${COMPARISON_HTML}")
  fi
fi

for output_path in "${OUTPUT_PATHS[@]}"; do
  if same_file "${output_path}" "${CANONICAL_BASELINE}"; then
    fail "output path must not overwrite the canonical baseline: ${output_path}"
  fi
  if [ "${COMPARE}" = true ] && same_file "${output_path}" "${BASELINE_PATH}"; then
    fail "output path must not overwrite the comparison baseline: ${output_path}"
  fi
done

for ((left_index = 0; left_index < ${#OUTPUT_PATHS[@]}; left_index++)); do
  for ((right_index = left_index + 1; right_index < ${#OUTPUT_PATHS[@]}; right_index++)); do
    if same_file "${OUTPUT_PATHS[left_index]}" "${OUTPUT_PATHS[right_index]}"; then
      fail "output paths must be distinct: ${OUTPUT_PATHS[left_index]}"
    fi
  done
done

cd "${ROOT_DIR}"

if [ -z "${GITHUB_SHA:-}" ]; then
  GITHUB_SHA="$(git rev-parse HEAD)"
  export GITHUB_SHA
fi
if [ -z "${GITHUB_REF:-}" ]; then
  GITHUB_REF="$(git symbolic-ref -q HEAD || printf 'HEAD')"
  export GITHUB_REF
fi

if [ -n "$(git status --porcelain -- "${SOURCE_PATH}")" ]; then
  printf 'Warning: %s is dirty; snapshot content may differ from base commit %s.\n' \
    "${SOURCE_PATH}" "${GITHUB_SHA}" >&2
fi

analysis_command=(
  "${SELF_ANALYSIS_BIN}"
  --source "${SOURCE_PATH}"
  --language "${LANGUAGE}"
  --repo-name "${REPO_NAME}"
  --algorithm "${ALGORITHM}"
  --output-json "${OUTPUT_JSON}"
)

analysis_command+=(--output-html "${OUTPUT_HTML}")

printf 'Analyzing %s with language=%s algorithm=%s\n' \
  "${SOURCE_PATH}" "${LANGUAGE}" "${ALGORITHM}"
"${analysis_command[@]}"
"${PYTHON_BIN}" -c '
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
data = json.loads(path.read_text(encoding="utf-8"))
data["analysis_source_path"] = sys.argv[2]
data["arcade_agent_version"] = sys.argv[3]
data["language"] = sys.argv[4]
data["algorithm"] = sys.argv[5]
data["repo_name"] = sys.argv[6]
path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
' "${OUTPUT_JSON}" "${SOURCE_PATH}" "${PINNED_ARCADE_AGENT_VERSION}" \
  "${LANGUAGE}" "${ALGORITHM}" "${REPO_NAME}"
printf 'Full snapshot: %s\n' "${OUTPUT_JSON}"
printf 'Analysis HTML: %s\n' "${OUTPUT_HTML}"

if [ "${COMPARE}" = true ]; then
  comparison_command=(
    "${COMPARE_BASELINE_BIN}"
    "${OUTPUT_JSON}"
    "${BASELINE_PATH}"
    --repo-name "${REPO_NAME}"
    --output "${COMPARISON_MD}"
  )

  if [ -n "${COMPARISON_HTML}" ]; then
    comparison_command+=(--output-html "${COMPARISON_HTML}")
  fi

  "${comparison_command[@]}"
  printf 'Baseline: %s\n' "${BASELINE_PATH}"
  printf 'Comparison: %s\n' "${COMPARISON_MD}"
  if [ -n "${COMPARISON_HTML}" ]; then
    printf 'Comparison HTML: %s\n' "${COMPARISON_HTML}"
  fi
fi

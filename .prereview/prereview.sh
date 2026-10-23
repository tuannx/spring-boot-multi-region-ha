#!/usr/bin/env bash
set -uo pipefail
CONFIG="${1:-prereview.yml}"
ROOT="$PWD"
WORKDIR="$(awk '/working-directory:/ {print $2; exit}' "$CONFIG")"
WORKDIR="${WORKDIR:-.}"
declare -A STATUS DURATION

run_node() {
  local id="$1"; shift
  local t0
  t0=$(date +%s)
  if "$@" >"/tmp/prereview-$id.log" 2>&1; then STATUS[$id]="passed"; else STATUS[$id]="failed"; fi
  DURATION[$id]=$(($(date +%s)-t0))
}
diff_plugin() {
  local base="${GITHUB_BASE_REF:-main}"
  git fetch -q origin "$base" || true
  git diff --name-only "origin/$base"...HEAD >/tmp/prereview-changed.txt 2>/dev/null || true
}
build_plugin() {
  cd "$ROOT/$WORKDIR"
  if [[ -x ./gradlew ]]; then ./gradlew classes
  elif [[ -f build.gradle || -f build.gradle.kts ]]; then gradle classes
  elif [[ -x ./mvnw ]]; then ./mvnw -q -DskipTests package
  elif [[ -f pom.xml ]]; then mvn -q -DskipTests package
  else echo "No Maven/Gradle build detected"; return 2; fi
}
test_plugin() {
  cd "$ROOT/$WORKDIR"
  if [[ -x ./gradlew ]]; then ./gradlew test
  elif [[ -f build.gradle || -f build.gradle.kts ]]; then gradle test
  elif [[ -x ./mvnw ]]; then ./mvnw -q test
  elif [[ -f pom.xml ]]; then mvn -q test
  else return 2; fi
}
structure_plugin() {
  cd "$ROOT/$WORKDIR"
  test -d src/main
  test -f build.gradle.kts -o -f build.gradle -o -f pom.xml
}

run_node diff diff_plugin
run_node build build_plugin &
P_BUILD=$!
run_node structure structure_plugin &
P_STRUCTURE=$!
wait $P_BUILD || true
wait $P_STRUCTURE || true
if [[ "${STATUS[build]:-failed}" == passed ]]; then run_node test test_plugin; else STATUS[test]="skipped"; DURATION[test]=0; fi

verdict="passed"
[[ "${STATUS[build]}" == failed || "${STATUS[test]}" == failed || "${STATUS[structure]}" == failed ]] && verdict="blocked"
changed=$(wc -l </tmp/prereview-changed.txt 2>/dev/null | tr -d ' ' || echo 0)
{
  echo "# PreReview - JVM / Spring Boot"
  echo
  if [[ "$verdict" == passed ]]; then echo "## PASS"; else echo "## BLOCKED"; fi
  echo
  echo "$changed changed files"
  echo
  echo "| Gate | Result | Time |"
  echo "|---|---:|---:|"
  for id in diff build test structure; do
    echo "| $id | ${STATUS[$id]:-unknown} | ${DURATION[$id]:-0}s |"
  done
  echo
  echo "<details><summary>Evaluation graph</summary>"
  echo
  echo '~~~text'
  echo 'diff'
  echo ' |- build -- test'
  echo ' `- structure'
  echo '~~~'
  echo "</details>"
  echo
  echo "Deterministic POC - no LLM judge"
} >> "${GITHUB_STEP_SUMMARY:-/dev/stdout}"
echo "PreReview verdict: $verdict"
[[ "$verdict" == passed ]]

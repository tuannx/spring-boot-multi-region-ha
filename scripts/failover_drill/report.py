from __future__ import annotations

import hashlib
import json
from datetime import datetime, timezone

from failover_drill.config import DISPLAY_METRICS, METRIC_PLACES, DrillConfig, DrillGates
from failover_drill.metrics import evaluate_gate, median_metric, metric_delta

MEASUREMENT_SCOPE = "local mock (containers + fault injection), not real Aurora Global"
ARCHITECTURE_DIFF_MARKER = "<!-- architecture-engineer-diff -->"
ARCHITECTURE_DIFF_UNAVAILABLE = "AE diff unavailable"
NIGHTLY_MEASUREMENT_SCOPE = "local mock (containers + Toxiproxy), not real Aurora Global"

FIELD_MEANINGS: tuple[tuple[str, str], ...] = (
    (
        "rto_seconds",
        "Seconds from `docker kill` of `multiregion-us` to the first HTTP 201 whose request started after promotion and whose row is stored on `postgres-eu`.",
    ),
    (
        "rpo_seconds",
        "Seconds from the oldest acknowledged write that is missing on the new writer back to the kill. Zero when every acknowledged write is present.",
    ),
    (
        "rpo_lost_writes",
        "Count of acknowledged product names missing on the new writer.",
    ),
    (
        "duplicate_writes",
        "Extra `products` rows for the same drill name. Server retries during the switch can insert the same name twice.",
    ),
    (
        "error_count",
        "Write responses that are not HTTP 201 and overlap the window from the kill until the recovered write.",
    ),
    (
        "p99_latency_ms",
        "Nearest-rank p99 of write latencies in that window. It is reported and has no absolute pass/fail limit.",
    ),
    (
        "fault_injected_at",
        "UTC time immediately before `docker kill` of `multiregion-us`.",
    ),
    (
        "writer_switched_at",
        "UTC time when `postgres-eu` has accepted writer mode.",
    ),
    ("commit_sha", "Git commit the drill measured."),
    ("run_count", "Number of independent iterations stored in the report."),
    ("config_sha256", "SHA-256 of the canonical failover drill config JSON."),
)


def aggregate_runs(runs: list[dict]) -> dict:
    aggregate: dict[str, object] = {"method": "median"}
    for name, _label, places in DISPLAY_METRICS:
        aggregate[name] = median_metric([run.get(name) for run in runs], places)
    return aggregate


def build_report(
    config: DrillConfig,
    runs: list[dict],
    commit_sha: str,
    workflow_run_url: str | None,
    runtime: dict,
    baseline: dict | None,
    gates: DrillGates | None = None,
) -> dict:
    aggregate = aggregate_runs(runs)
    baseline_aggregate = None if baseline is None else baseline.get("aggregate")
    deltas = {}
    if isinstance(baseline_aggregate, dict):
        for name, _label, _places in DISPLAY_METRICS:
            deltas[name] = metric_delta(
                name,
                aggregate.get(name) if isinstance(aggregate.get(name), (int, float)) else None,
                baseline_aggregate.get(name) if isinstance(baseline_aggregate.get(name), (int, float)) else None,
                config.gates.regression_ratio_max,
                config.gates.regression_min_deltas(),
            )
    gate = evaluate_gate(
        aggregate,
        baseline_aggregate if isinstance(baseline_aggregate, dict) else None,
        config.gates if gates is None else gates,
        runs,
    )
    return {
        "schema_version": 1,
        "commit_sha": commit_sha,
        "workflow_run_url": workflow_run_url,
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "run_count": len(runs),
        "config_sha256": config_sha256(config),
        "config": config.to_dict(),
        "runtime": runtime,
        "runs": runs,
        "aggregate": aggregate,
        "baseline": baseline,
        "deltas": deltas,
        "gate": gate,
    }


def failure_report(config: DrillConfig, commit_sha: str, workflow_run_url: str | None, message: str) -> dict:
    empty_runs: list[dict] = []
    report = build_report(
        config,
        empty_runs,
        commit_sha,
        workflow_run_url,
        {"failure_injection": "docker-kill-primary", "status": "failed-before-measurement"},
        None,
    )
    report["aggregate"] = {name: None for name, _label, _places in DISPLAY_METRICS}
    report["aggregate"]["method"] = "median"
    report["gate"] = {"passed": False, "failures": [message]}
    return report


def write_report(path, report: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def render_comment(report: dict) -> str:
    aggregate = report["aggregate"]
    deltas = report.get("deltas") or {}
    baseline = report.get("baseline")
    baseline_aggregate = baseline.get("aggregate") if isinstance(baseline, dict) else None
    gate = report["gate"]
    lines = [
        "<!-- failover-drill-report -->",
        "",
        "## Failover drill",
        "",
    ]
    iterations = report.get("config", {}).get("iterations")
    sha = report.get("commit_sha") or "unknown"
    intro = f"Median of {iterations} runs for `{sha}`."
    run_url = report.get("workflow_run_url")
    if run_url:
        intro += f" [Workflow run]({run_url})."
    lines.append(intro)
    lines.append("")
    lines.append(f"These numbers are a {MEASUREMENT_SCOPE}.")
    lines.append("")
    status = "passed" if gate.get("passed") else "failed"
    lines.append(f"Gate: **{status}**")
    lines.append("")
    lines.extend(_results_table(aggregate, baseline_aggregate, deltas))
    lines.append("")
    lines.append(
        "RTO is the time from killing the primary database to the first write that "
        "started after the kill and is stored on the new writer. RPO is the time from "
        "the oldest missing acknowledged write to that kill, or 0 when every acknowledged "
        "write is on the new writer. Errors and p99 cover write attempts that overlap "
        "that window. p99 has no absolute limit."
    )
    lines.append("")
    lines.append(_threshold_sentence(_applied_gates(report)))
    failures = gate.get("failures") or []
    if failures:
        lines.append("")
        lines.append("Failures:")
        for failure in failures:
            lines.append(f"- {failure}")
    if not isinstance(baseline_aggregate, dict):
        lines.append("")
        lines.append(
            "No main baseline artifact was available, so regression deltas were not applied. "
            "The baseline is kept for 90 days and refreshed by the nightly failover drill on main. "
            "Absolute RTO, RPO, lost-write, and duplicate-write limits still apply."
        )
    lines.append("")
    lines.append(
        "Each run records `fault_injected_at` and `writer_switched_at` in UTC. "
        "The `otlp-trace.json` artifact is newline-delimited OTLP JSON from the applications "
        "before, during, and after those timestamps."
    )
    lines.append("")
    lines.append("## Architecture diff")
    lines.append("")
    lines.append(ARCHITECTURE_DIFF_MARKER)
    lines.append("")
    return "\n".join(lines)


def record_unavailable_architecture_diff(comment: str) -> tuple[str, bool]:
    start = comment.find(ARCHITECTURE_DIFF_MARKER)
    if start < 0:
        return comment, False
    body = comment[start + len(ARCHITECTURE_DIFF_MARKER) :]
    if body.strip():
        return comment, False
    prefix = comment[:start]
    updated = f"{prefix}{ARCHITECTURE_DIFF_MARKER}\n{ARCHITECTURE_DIFF_UNAVAILABLE}\n"
    return updated, True


def render_readme_section(config: DrillConfig) -> str:
    aggregate = {name: None for name, _label, _places in DISPLAY_METRICS}
    lines = [
        f"These numbers are a {MEASUREMENT_SCOPE}.",
        "",
        "The measured results table is rendered from `drill-report.json` by the same function that writes the pull-request comment. "
        "The rows below are that function applied to `config/failover-drill.json` with an empty sample, so this section is not maintained by hand.",
        "",
    ]
    lines.extend(_results_table(aggregate, None, {}))
    lines.append("")
    lines.append("| Field | Meaning |")
    lines.append("| --- | --- |")
    for name, meaning in FIELD_MEANINGS:
        lines.append(f"| `{name}` | {meaning} |")
    lines.append("")
    gates = dict(config.to_dict().get("gates", {}))
    primary = next(scenario for scenario in config.scenarios if scenario.name == "primary-kill")
    gates["rpo_seconds_max"] = primary.rpo_seconds_max
    gates["rpo_lost_writes_max"] = primary.rpo_lost_writes_max
    lines.append(_threshold_sentence(gates))
    return "\n".join(lines) + "\n"


def _results_table(aggregate: dict, baseline_aggregate, deltas: dict) -> list[str]:
    lines = [
        "| Metric | This change | main | Delta |",
        "| --- | ---: | ---: | ---: |",
    ]
    for name, label, places in DISPLAY_METRICS:
        current = _format_metric(aggregate.get(name), places)
        if isinstance(baseline_aggregate, dict):
            main_value = _format_metric(baseline_aggregate.get(name), places)
            delta = _format_delta(deltas.get(name), places)
        else:
            main_value = "—"
            delta = "n/a"
        lines.append(f"| {label} | {current} | {main_value} | {delta} |")
    return lines


def _applied_gates(report: dict) -> dict:
    config = report.get("config") if isinstance(report.get("config"), dict) else {}
    gates = dict(config.get("gates") or {})
    scenario_name = report.get("scenario")
    for scenario in config.get("scenarios") or []:
        if isinstance(scenario, dict) and scenario.get("name") == scenario_name:
            if "rpo_seconds_max" in scenario:
                gates["rpo_seconds_max"] = scenario["rpo_seconds_max"]
            if "rpo_lost_writes_max" in scenario:
                gates["rpo_lost_writes_max"] = scenario["rpo_lost_writes_max"]
            break
    return gates


def _threshold_text(value: object) -> str:
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value)


def _threshold_sentence(gates: dict) -> str:
    metrics = ", ".join(gates.get("regression_metrics") or [])
    return (
        "Thresholds from `config/failover-drill.json`: "
        f"RTO ≤ {_threshold_text(gates.get('rto_seconds_max'))}s, "
        f"RPO ≤ {_threshold_text(gates.get('rpo_seconds_max'))}s, "
        f"rpo_lost_writes ≤ {_threshold_text(gates.get('rpo_lost_writes_max'))}, "
        f"duplicate_writes ≤ {gates.get('duplicate_writes_max')}, "
        f"regression ≤ {float(gates.get('regression_ratio_max', 0)) * 100:.0f}% "
        f"on {metrics}. "
        "An RTO regression also has to increase by at least "
        f"{gates.get('rto_regression_min_delta_seconds')}s, "
        "an RPO regression by at least "
        f"{gates.get('rpo_regression_min_delta_seconds')}s, "
        "and an error-count regression by at least "
        f"{gates.get('error_count_regression_min_delta')}."
    )


def config_sha256(config: DrillConfig) -> str:
    payload = json.dumps(config.to_dict(), sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _format_metric(value, places: int) -> str:
    if value is None:
        return "—"
    if places == 0:
        return str(int(value))
    return f"{float(value):.{places}f}"


def _format_delta(delta: dict | None, places: int) -> str:
    if not delta:
        return "n/a"
    absolute = delta["absolute"]
    if places == 0:
        absolute_text = f"{int(absolute):+d}"
    else:
        absolute_text = f"{float(absolute):+.{places}f}"
    if delta.get("unbounded"):
        ratio_text = "from 0"
    elif delta.get("ratio") is None:
        ratio_text = "0.0%"
    else:
        ratio_text = f"{float(delta['ratio']) * 100:+.1f}%"
    return f"{absolute_text} ({ratio_text}) {delta['direction']}"


def load_baseline(path) -> dict | None:
    if path is None or not path.is_file():
        return None
    payload = json.loads(path.read_text(encoding="utf-8"))
    aggregate = payload.get("aggregate")
    if not isinstance(aggregate, dict):
        raise ValueError(f"Baseline {path} has no aggregate")
    for name in METRIC_PLACES:
        if name not in aggregate:
            raise ValueError(f"Baseline {path} is missing {name}")
    return {
        "commit_sha": payload.get("commit_sha"),
        "source": str(path),
        "aggregate": {name: aggregate.get(name) for name in METRIC_PLACES},
    }

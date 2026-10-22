from __future__ import annotations

from failover_drill.config import DISPLAY_METRICS
from failover_drill.report import (
    MEASUREMENT_SCOPE,
    NIGHTLY_MEASUREMENT_SCOPE,
    _results_table,
)


def measurement_scope_for(scenario_name: str) -> str:
    if scenario_name == "primary-kill":
        return MEASUREMENT_SCOPE
    return NIGHTLY_MEASUREMENT_SCOPE


def combine_scenario_reports(reports: list[dict]) -> dict:
    if not reports:
        raise ValueError("nightly report has no scenarios")
    primary = next((report for report in reports if report.get("scenario") == "primary-kill"), reports[0])
    failures: list[str] = []
    for report in reports:
        name = str(report.get("scenario") or "unknown")
        gate = report.get("gate") or {}
        if gate.get("passed"):
            continue
        for failure in gate.get("failures") or ["scenario failed"]:
            failures.append(f"{name}: {failure}")
    combined = dict(primary)
    combined["profile"] = "nightly"
    combined["measurement_scope"] = NIGHTLY_MEASUREMENT_SCOPE
    combined["scenarios"] = reports
    combined["nightly_gate"] = {"passed": not failures, "failures": failures}
    return combined


def scenario_passed(report: dict, name: str) -> bool:
    scenarios = report.get("scenarios")
    if not isinstance(scenarios, list):
        return name == report.get("scenario") and bool((report.get("gate") or {}).get("passed"))
    for scenario in scenarios:
        if scenario.get("scenario") == name:
            return bool((scenario.get("gate") or {}).get("passed"))
    return False


def render_nightly_markdown(report: dict) -> str:
    lines = [
        "## Nightly failover drill",
        "",
        f"These numbers are a {NIGHTLY_MEASUREMENT_SCOPE}.",
        "",
    ]
    sha = report.get("commit_sha") or "unknown"
    run_url = report.get("workflow_run_url")
    intro = f"Commit `{sha}`."
    if run_url:
        intro += f" [Workflow run]({run_url})."
    lines.append(intro)
    lines.append("")
    nightly_gate = report.get("nightly_gate") or report.get("gate") or {}
    status = "passed" if nightly_gate.get("passed") else "failed"
    lines.append(f"Nightly gate: **{status}**.")
    lines.append("")
    lines.append(
        "The `primary-kill` aggregate is the baseline pull requests compare. "
        "It uses the same container kill as the pull-request drill and does not pass through Toxiproxy. "
        "`network-partition` and `slow-db` run only on this nightly workflow."
    )
    scenarios = report.get("scenarios")
    if not isinstance(scenarios, list):
        scenarios = [report]
    for scenario in scenarios:
        lines.extend(_scenario_section(scenario))
    failures = nightly_gate.get("failures") or []
    if failures:
        lines.append("")
        lines.append("Failures:")
        for failure in failures:
            lines.append(f"- {failure}")
    lines.append("")
    return "\n".join(lines)


def _scenario_section(report: dict) -> list[str]:
    name = str(report.get("scenario") or "primary-kill")
    scope = str(report.get("measurement_scope") or measurement_scope_for(name))
    gate = report.get("gate") or {}
    status = "passed" if gate.get("passed") else "failed"
    lines = [
        "",
        f"### {name}",
        "",
        f"These numbers are a {scope}.",
        "",
        f"Gate: **{status}**. Median of {report.get('run_count')} runs. "
        f"`config_sha256` `{report.get('config_sha256')}`.",
        "",
    ]
    lines.extend(
        _results_table(
            report.get("aggregate") or {},
            (report.get("baseline") or {}).get("aggregate") if isinstance(report.get("baseline"), dict) else None,
            report.get("deltas") or {},
        )
    )
    lines.append("")
    lines.append("| Field | Value |")
    lines.append("| --- | --- |")
    lines.append(f"| `commit_sha` | `{report.get('commit_sha')}` |")
    lines.append(f"| `run_count` | {report.get('run_count')} |")
    lines.append(f"| `config_sha256` | `{report.get('config_sha256')}` |")
    for run in report.get("runs") or []:
        index = run.get("index")
        lines.append(
            f"| run {index} `fault_injected_at` | `{run.get('fault_injected_at')}` |"
        )
        lines.append(
            f"| run {index} `writer_switched_at` | `{run.get('writer_switched_at')}` |"
        )
    runtime = report.get("runtime") or {}
    if runtime.get("toxiproxy_latency_ms"):
        lines.append("")
        lines.append(
            f"Slow-db latency is a fixed {runtime['toxiproxy_latency_ms']} ms downstream delay "
            f"with jitter {runtime.get('toxiproxy_jitter_ms', 0)} ms and toxicity "
            f"{runtime.get('toxiproxy_toxicity', 1)}."
        )
    if runtime.get("failure_injection"):
        lines.append("")
        lines.append(f"Fault: `{runtime['failure_injection']}`.")
    missing = [metric for metric, _label, _places in DISPLAY_METRICS if metric not in (report.get("aggregate") or {})]
    if missing:
        lines.append("")
        lines.append("Missing aggregate fields: " + ", ".join(f"`{name}`" for name in missing) + ".")
    return lines

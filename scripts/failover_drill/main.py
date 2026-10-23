from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

from failover_drill.config import ScenarioConfig, load_config, resolve_profile, select_scenarios
from failover_drill.metrics import (
    duplicate_writes,
    read_error_count,
    recovery_point,
    recovery_time_seconds,
    switchover_error_count,
    switchover_p99_ms,
    writes,
)
from failover_drill.nightly_report import (
    combine_scenario_reports,
    measurement_scope_for,
    render_nightly_markdown,
    scenario_passed,
    should_publish_baseline,
)
from failover_drill.replication import (
    PRIMARY,
    create_subscription,
    ensure_primary,
    product_counts,
    promote_secondary,
    reset_subscription,
    wait_until_caught_up,
)
from failover_drill.report import build_report, failure_report, load_baseline, render_comment, write_report
from failover_drill.stack import ComposeStack, StackError
from failover_drill.toxiproxy import ToxiproxyClient
from failover_drill.traces import uncovered_iterations, utc_timestamp
from failover_drill.workload import execute_workload


def main(argv: list[str] | None = None) -> int:
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description="Run the local Aurora failover drill.")
    parser.add_argument(
        "--config",
        type=Path,
        default=root / "config" / "failover-drill.json",
    )
    parser.add_argument("--baseline", type=Path)
    parser.add_argument(
        "--output",
        type=Path,
        default=root / "reports" / "failover-drill" / "drill-report.json",
    )
    parser.add_argument("--profile", default=None)
    args = parser.parse_args(argv)
    config = load_config(args.config)
    profile = resolve_profile(args.profile or os.environ.get("FAILOVER_DRILL_PROFILE"))
    scenarios = select_scenarios(config, profile)
    commit_sha = _commit_sha(root)
    run_url = os.environ.get("FAILOVER_DRILL_RUN_URL") or None
    baseline = load_baseline(args.baseline)
    output = args.output
    comment_path = output.parent / "comment.md"
    keep = os.environ.get("FAILOVER_DRILL_KEEP") == "1"
    reports = [
        _run_scenario(
            root,
            config,
            scenario,
            profile,
            commit_sha,
            run_url,
            baseline,
            keep and scenario is scenarios[-1],
        )
        for scenario in scenarios
    ]
    report = reports[0] if profile == "pull-request" else combine_scenario_reports(reports)
    write_report(output, report)
    comment = render_comment(report) if profile == "pull-request" else render_nightly_markdown(report)
    comment_path.write_text(comment, encoding="utf-8")
    print(comment, flush=True)
    ref = os.environ.get("FAILOVER_DRILL_REF", os.environ.get("GITHUB_REF", ""))
    if should_publish_baseline(profile, ref, scenario_passed(report, "primary-kill")):
        (output.parent / "baseline-eligible").write_text("primary-kill\n", encoding="utf-8")
    if profile == "nightly":
        return 0 if (report.get("nightly_gate") or {}).get("passed") else 1
    return 0 if report["gate"]["passed"] else 1


def _run_scenario(
    root,
    config,
    scenario: ScenarioConfig,
    profile: str,
    commit_sha,
    run_url,
    baseline,
    keep: bool,
) -> dict:
    output_dir = root / "reports" / "failover-drill"
    output_dir.mkdir(parents=True, exist_ok=True)
    stack = ComposeStack(root, scenario.compose_overlay)
    scenario_baseline = baseline if scenario.name == "primary-kill" else None
    try:
        return _execute(
            stack,
            config,
            scenario,
            profile,
            commit_sha,
            run_url,
            scenario_baseline,
            keep,
            output_dir,
        )
    except Exception as exc:
        report = failure_report(config, commit_sha, run_url, str(exc))
        _annotate(report, scenario, profile)
        report["runtime"]["scenario"] = scenario.name
        report["runtime"]["failure_injection"] = scenario.fault
        report["runtime"]["measurement_scope"] = measurement_scope_for(scenario.name)
        log_path = _log_path(output_dir, scenario)
        if not log_path.exists():
            _preserve_logs(stack, log_path)
        if not keep:
            stack.down()
        return report


def _execute(
    stack,
    config,
    scenario: ScenarioConfig,
    profile: str,
    commit_sha,
    run_url,
    baseline,
    keep: bool,
    output_dir: Path,
) -> dict:
    stack.down()
    built = stack.ensure_images()
    toxiproxy = _toxiproxy_client(config, scenario)
    report: dict | None = None
    try:
        stack.up()
        timeout = config.stack_ready_timeout_seconds
        if toxiproxy is not None:
            _prepare_proxy(toxiproxy, config, scenario, timeout)
        ensure_primary(stack, timeout)
        stack.wait_http("http://localhost:8080", timeout)
        stack.wait_http(config.app_eu_url, timeout)
        runs = [
            _run_iteration(stack, config, scenario, toxiproxy, index)
            for index in range(1, config.iterations + 1)
        ]
        report = build_report(
            config,
            runs,
            commit_sha,
            run_url,
            _runtime(config, scenario, built),
            baseline,
        )
        _annotate(report, scenario, profile)
    except Exception:
        _preserve_logs(stack, _log_path(output_dir, scenario))
        raise
    finally:
        trace_path = _trace_path(output_dir, scenario)
        exported = stack.export_traces(trace_path, stop_applications=not keep)
        if scenario.name == "primary-kill" and trace_path != output_dir / "otlp-trace.json":
            legacy = output_dir / "otlp-trace.json"
            if trace_path.is_file():
                legacy.write_bytes(trace_path.read_bytes())
        if not keep:
            stack.down()
    if report is None:
        raise StackError(f"{scenario.name} did not produce a report")
    if not exported:
        _fail_trace_gate(report, "otlp-trace.json was not exported")
        return report
    missing = uncovered_iterations(trace_path.read_text(encoding="utf-8"), report["runs"])
    if missing:
        joined = ", ".join(str(index) for index in missing)
        _fail_trace_gate(
            report,
            "otlp-trace.json does not cover before, during, and after the writer switch "
            f"for iteration {joined}",
        )
    else:
        report["runtime"]["traces_cover_failover"] = True
    return report


def _run_iteration(stack: ComposeStack, config, scenario: ScenarioConfig, toxiproxy: ToxiproxyClient | None, index: int) -> dict:
    print(f"{scenario.name} iteration {index}: preparing replication", flush=True)
    timeout = config.stack_ready_timeout_seconds
    if toxiproxy is not None:
        _prepare_proxy(toxiproxy, config, scenario, timeout)
    ensure_primary(stack, timeout)
    stack.stop_apps()
    reset_subscription(stack)
    create_subscription(stack, scenario.replication_host)
    wait_until_caught_up(stack, index, config.replication_catchup_timeout_seconds)
    stack.start_apps()
    stack.wait_http("http://localhost:8080", timeout)
    stack.wait_http(config.app_eu_url, timeout)

    fault_injected_at = ""
    writer_switched_at = ""

    def inject_fault() -> None:
        nonlocal fault_injected_at, writer_switched_at
        fault_injected_at = utc_timestamp()
        if scenario.fault == "docker-kill-primary":
            print(f"{scenario.name} iteration {index}: killing {PRIMARY}", flush=True)
            stack.kill(PRIMARY)
        elif scenario.fault == "toxiproxy-partition":
            print(f"{scenario.name} iteration {index}: partitioning {config.toxiproxy.proxy_name}", flush=True)
            if toxiproxy is None:
                raise StackError("network partition requires Toxiproxy")
            toxiproxy.disable(config.toxiproxy.proxy_name)
        else:
            raise StackError(f"unknown fault {scenario.fault}")
        promote_secondary(stack)
        writer_switched_at = utc_timestamp()

    workload = execute_workload(
        base_url=config.app_eu_url,
        run_index=index,
        warmup_seconds=config.warmup_seconds,
        recovery_timeout_seconds=config.recovery_timeout_seconds,
        write_workers=config.write_workers,
        read_workers=config.read_workers,
        write_interval_ms=config.write_interval_ms,
        read_interval_ms=config.read_interval_ms,
        client_timeout_seconds=config.client_timeout_seconds,
        inject_fault=inject_fault,
    )
    counts = product_counts(stack, index)
    present = set(counts)
    recovery_timeout_ms = int(config.recovery_timeout_seconds * 1000)
    rto = recovery_time_seconds(
        workload.attempts,
        workload.fault_ms,
        workload.eligible_start_ms,
        present,
    )
    rpo_seconds, lost = recovery_point(workload.attempts, workload.fault_ms, present)
    errors = switchover_error_count(
        workload.attempts,
        workload.fault_ms,
        workload.eligible_start_ms,
        present,
        recovery_timeout_ms,
    )
    p99 = switchover_p99_ms(
        workload.attempts,
        workload.fault_ms,
        workload.eligible_start_ms,
        present,
        recovery_timeout_ms,
    )
    us_status = stack.container_status(PRIMARY)
    eu_writer = stack.psql("multiregion-eu", "SELECT pg_catalog.aurora_is_writer();")
    proxy_enabled = None if scenario.fault != "toxiproxy-partition" else toxiproxy.is_enabled(config.toxiproxy.proxy_name)
    verified = _verified(scenario, us_status, eu_writer == "t", rto, proxy_enabled)
    acknowledged = len({attempt.name for attempt in writes(workload.attempts) if attempt.status == 201})
    run = {
        "index": index,
        "rto_seconds": rto,
        "rpo_seconds": rpo_seconds,
        "rpo_lost_writes": lost,
        "duplicate_writes": duplicate_writes(counts),
        "error_count": errors,
        "p99_latency_ms": p99,
        "acknowledged_writes": acknowledged,
        "read_error_count": read_error_count(
            workload.attempts,
            workload.fault_ms,
            workload.eligible_start_ms,
            present,
            recovery_timeout_ms,
        ),
        "driver_failover_verified": verified,
        "primary_container_status": us_status,
        "promoted_writer_mode": eu_writer == "t",
        "fault_injected_at": fault_injected_at,
        "writer_switched_at": writer_switched_at,
    }
    print(
        f"{scenario.name} iteration {index}: rto={run['rto_seconds']}s rpo={run['rpo_seconds']}s "
        f"lost={lost} duplicates={run['duplicate_writes']} errors={errors} p99_ms={p99}",
        flush=True,
    )
    return run


def _verified(scenario: ScenarioConfig, us_status: str, eu_writer: bool, rto: float | None, proxy_enabled: bool | None) -> bool:
    if rto is None or not eu_writer:
        return False
    if scenario.fault == "toxiproxy-partition":
        return proxy_enabled is False and us_status == "running"
    return us_status == "exited"


def _prepare_proxy(toxiproxy: ToxiproxyClient, config, scenario: ScenarioConfig, timeout_seconds: float) -> None:
    proxy = config.toxiproxy
    toxiproxy.wait_ready(timeout_seconds)
    toxiproxy.ensure_proxy(proxy.proxy_name, proxy.listen, proxy.upstream)
    toxiproxy.enable(proxy.proxy_name)
    toxiproxy.clear_toxics(proxy.proxy_name)
    if scenario.latency_ms > 0:
        toxiproxy.add_latency(proxy.proxy_name, scenario.latency_ms, scenario.jitter_ms, scenario.toxicity)


def _toxiproxy_client(config, scenario: ScenarioConfig) -> ToxiproxyClient | None:
    if not scenario.uses_toxiproxy:
        return None
    return ToxiproxyClient(config.toxiproxy.api_url)


def _runtime(config, scenario: ScenarioConfig, built: bool) -> dict:
    runtime = {
        "scenario": scenario.name,
        "failure_injection": scenario.fault,
        "primary_container": PRIMARY,
        "promoted_writer": "postgres-eu",
        "workload_url": config.app_eu_url,
        "wrapper_dialect": "aurora-pg",
        "cluster_instance_host_pattern": "?.proxy:5432" if scenario.uses_toxiproxy else "?:5432",
        "replication": f"logical-products-{scenario.replication_host}-to-eu",
        "replication_host": scenario.replication_host,
        "images_reused": not built,
        "application_promotion": "control-plane set_writer_mode; driver failover2 reconnects",
        "traces_file": "otlp-trace.json",
        "traces_format": "otlp-json-lines",
        "otel_agent": "opentelemetry-javaagent",
        "otel_collector": "file-exporter",
        "measurement_scope": measurement_scope_for(scenario.name),
    }
    if scenario.uses_toxiproxy:
        runtime["toxiproxy_image"] = config.toxiproxy.image
        runtime["toxiproxy_proxy"] = config.toxiproxy.proxy_name
        runtime["toxiproxy_upstream"] = config.toxiproxy.upstream
        runtime["toxiproxy_latency_ms"] = scenario.latency_ms
        runtime["toxiproxy_jitter_ms"] = scenario.jitter_ms
        runtime["toxiproxy_toxicity"] = scenario.toxicity
    return runtime


def _annotate(report: dict, scenario: ScenarioConfig, profile: str) -> None:
    report["scenario"] = scenario.name
    report["profile"] = profile
    report["measurement_scope"] = measurement_scope_for(scenario.name)


def _trace_path(output_dir: Path, scenario: ScenarioConfig) -> Path:
    if scenario.name == "primary-kill":
        return output_dir / "otlp-trace.json"
    return output_dir / "scenarios" / scenario.name / "otlp-trace.json"


def _log_path(output_dir: Path, scenario: ScenarioConfig) -> Path:
    if scenario.name == "primary-kill":
        return output_dir / "compose.log"
    return output_dir / "scenarios" / scenario.name / "compose.log"


def _fail_trace_gate(report: dict, message: str) -> None:
    report["runtime"]["traces_cover_failover"] = False
    report["gate"]["passed"] = False
    failures = list(report["gate"].get("failures") or [])
    failures.append(message)
    report["gate"]["failures"] = failures


def _preserve_logs(stack: ComposeStack, path: Path) -> None:
    try:
        stack.write_logs(path)
    except StackError:
        return


def _commit_sha(root: Path) -> str:
    override = os.environ.get("FAILOVER_DRILL_COMMIT_SHA") or os.environ.get("GITHUB_SHA")
    if override:
        return override
    completed = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        return "unknown"
    return completed.stdout.strip()


if __name__ == "__main__":
    sys.exit(main())

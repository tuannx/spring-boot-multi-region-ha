from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

from failover_drill.config import load_config
from failover_drill.metrics import (
    duplicate_writes,
    read_error_count,
    recovery_point,
    recovery_time_seconds,
    switchover_error_count,
    switchover_p99_ms,
    writes,
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
    args = parser.parse_args(argv)
    config = load_config(args.config)
    commit_sha = _commit_sha(root)
    run_url = os.environ.get("FAILOVER_DRILL_RUN_URL") or None
    baseline = load_baseline(args.baseline)
    output = args.output
    comment_path = output.parent / "comment.md"
    stack = ComposeStack(root)
    keep = os.environ.get("FAILOVER_DRILL_KEEP") == "1"
    try:
        report = _execute(root, stack, config, commit_sha, run_url, baseline, keep)
    except Exception as exc:
        report = failure_report(config, commit_sha, run_url, str(exc))
        write_report(output, report)
        comment_path.write_text(render_comment(report), encoding="utf-8")
        print(render_comment(report), flush=True)
        if not keep:
            log_path = output.parent / "compose.log"
            if not log_path.exists():
                _preserve_logs(stack, output.parent)
            stack.down()
        return 1
    write_report(output, report)
    comment = render_comment(report)
    comment_path.write_text(comment, encoding="utf-8")
    print(comment, flush=True)
    return 0 if report["gate"]["passed"] else 1


def _execute(root, stack, config, commit_sha, run_url, baseline, keep: bool) -> dict:
    output_dir = root / "reports" / "failover-drill"
    output_dir.mkdir(parents=True, exist_ok=True)
    stack.down()
    built = stack.ensure_images()
    try:
        stack.up()
        timeout = config.stack_ready_timeout_seconds
        ensure_primary(stack, timeout)
        stack.wait_http("http://localhost:8080", timeout)
        stack.wait_http(config.app_eu_url, timeout)
        runs = [
            _run_iteration(stack, config, index)
            for index in range(1, config.iterations + 1)
        ]
        runtime = {
            "failure_injection": "docker-kill-primary",
            "primary_container": PRIMARY,
            "promoted_writer": "postgres-eu",
            "workload_url": config.app_eu_url,
            "wrapper_dialect": "aurora-pg",
            "cluster_instance_host_pattern": "?:5432",
            "replication": "logical-products-us-to-eu",
            "images_reused": not built,
            "application_promotion": "control-plane set_writer_mode; driver failover2 reconnects",
            "traces_file": "otlp-trace.json",
            "traces_format": "otlp-json-lines",
            "otel_agent": "opentelemetry-javaagent",
            "otel_collector": "file-exporter",
        }
        report = build_report(config, runs, commit_sha, run_url, runtime, baseline)
    except Exception:
        _preserve_logs(stack, output_dir)
        raise
    finally:
        trace_path = output_dir / "otlp-trace.json"
        exported = stack.export_traces(trace_path, stop_applications=not keep)
        if not keep:
            stack.down()
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


def _run_iteration(stack: ComposeStack, config, index: int) -> dict:
    print(f"failover drill iteration {index}: preparing replication", flush=True)
    timeout = config.stack_ready_timeout_seconds
    ensure_primary(stack, timeout)
    stack.stop_apps()
    reset_subscription(stack)
    create_subscription(stack)
    wait_until_caught_up(stack, index, config.replication_catchup_timeout_seconds)
    stack.start_apps()
    stack.wait_http("http://localhost:8080", timeout)
    stack.wait_http(config.app_eu_url, timeout)

    fault_injected_at = ""
    writer_switched_at = ""

    def inject_fault() -> None:
        nonlocal fault_injected_at, writer_switched_at
        print(f"failover drill iteration {index}: killing {PRIMARY}", flush=True)
        fault_injected_at = utc_timestamp()
        stack.kill(PRIMARY)
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
    verified = us_status == "exited" and eu_writer == "t" and rto is not None
    acknowledged = len({attempt.name for attempt in writes(workload.attempts) if attempt.status == 201})
    run = {
        "index": index,
        "rto_seconds": rto,
        "rpo_seconds": rpo_seconds,
        "lost_acknowledged_writes": lost,
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
        "failover drill iteration "
        f"{index}: rto={run['rto_seconds']}s rpo={run['rpo_seconds']}s "
        f"lost={lost} duplicates={run['duplicate_writes']} errors={errors} p99_ms={p99}",
        flush=True,
    )
    return run


def _fail_trace_gate(report: dict, message: str) -> None:
    report["runtime"]["traces_cover_failover"] = False
    report["gate"]["passed"] = False
    failures = list(report["gate"].get("failures") or [])
    failures.append(message)
    report["gate"]["failures"] = failures


def _preserve_logs(stack: ComposeStack, output_dir: Path) -> None:
    try:
        stack.write_logs(output_dir / "compose.log")
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

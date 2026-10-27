from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path

from failover_drill.config import ACTIVE_REGION_IDS, DrillConfig, ScenarioConfig, scenario_gates
from failover_drill.metrics import (
    Attempt,
    duplicate_writes,
    first_recovered_write,
    nearest_rank,
    recovery_point,
    slowest_recovery_seconds,
    write_conflicts,
    writes,
)
from failover_drill.nightly_report import measurement_scope_for
from failover_drill.report import build_report, failure_report
from failover_drill.stack import ComposeStack, StackError
from failover_drill.toxiproxy import ToxiproxyClient
from failover_drill.traces import utc_timestamp
from failover_drill.workload import Clock, WorkloadControl, request_status

PROJECT_NAME = "active-regions"
ROUTER_URL = "http://127.0.0.1:8000"
ACTIVE_READY_TIMEOUT_SECONDS = 420
APP_IMAGE = "multiregion-app-us:latest"
ROUTER_IMAGE = "multiregion-servicetalk-router:latest"


@dataclass(frozen=True)
class Region:
    region_id: str
    code: str
    host: str
    container: str
    app_container: str
    app_service: str
    app_host_port: int
    sequence_start: int

    @property
    def postgres_service(self) -> str:
        return self.host


REGIONS: tuple[Region, ...] = (
    Region("us-east-1", "use1", "postgres-us", "multiregion-us", "multiregion-app-us", "app-us", 8080, 1_000_000_000),
    Region("eu-west-1", "euw1", "postgres-eu", "multiregion-eu", "multiregion-app-eu", "app-eu", 8081, 2_000_000_000),
    Region("ap-southeast-1", "aps1", "postgres-apse1", "multiregion-apse1", "multiregion-app-apse1", "app-apse1", 8082, 3_000_000_000),
    Region("ap-northeast-1", "apn1", "postgres-apne1", "multiregion-apne1", "multiregion-app-apne1", "app-apne1", 8083, 4_000_000_000),
)

PAIR: dict[str, str] = {
    "us-east-1": "eu-west-1",
    "eu-west-1": "us-east-1",
    "ap-southeast-1": "ap-northeast-1",
    "ap-northeast-1": "ap-southeast-1",
}

SIDES: dict[str, str] = {
    "us-east-1": "west",
    "eu-west-1": "west",
    "ap-southeast-1": "east",
    "ap-northeast-1": "east",
}

PROXY_PORT: dict[str, int] = {
    "us-east-1": 5601,
    "eu-west-1": 5602,
    "ap-southeast-1": 5603,
    "ap-northeast-1": 5604,
}

SYNC_TARGET: dict[str, dict[str, str]] = {
    "lose-1-of-4": {
        "us-east-1": "eu-west-1",
        "eu-west-1": "us-east-1",
        "ap-southeast-1": "ap-northeast-1",
        "ap-northeast-1": "us-east-1",
    },
    "lose-2-of-4": {
        "us-east-1": "ap-southeast-1",
        "eu-west-1": "us-east-1",
        "ap-southeast-1": "us-east-1",
        "ap-northeast-1": "ap-southeast-1",
    },
    "partition-2-2": {
        "us-east-1": "eu-west-1",
        "eu-west-1": "us-east-1",
        "ap-southeast-1": "ap-northeast-1",
        "ap-northeast-1": "ap-southeast-1",
    },
}


def region_by_id(region_id: str) -> Region:
    for region in REGIONS:
        if region.region_id == region_id:
            return region
    raise KeyError(region_id)


def sync_target(scenario_name: str, region_id: str) -> str:
    return SYNC_TARGET[scenario_name][region_id]


def publication_name(owner: Region) -> str:
    return f"shard_{owner.code}"


def application_name(owner: Region, subscriber: Region) -> str:
    return f"shard_{owner.code}_to_{subscriber.code}"


def proxy_name(owner: Region) -> str:
    return f"shard-{owner.code}"


def replication_endpoint(owner: Region, subscriber: Region, fault: str) -> tuple[str, int]:
    if fault == "split-pairs" and SIDES[owner.region_id] != SIDES[subscriber.region_id]:
        return ("toxiproxy", PROXY_PORT[owner.region_id])
    return (owner.host, 5432)


def sequence_sql(region: Region) -> str:
    return (
        "ALTER SEQUENCE products_id_seq AS bigint MAXVALUE 9223372036854775807;\n"
        f"SELECT setval('products_id_seq', {region.sequence_start}, false);"
    )


def publication_sql(owner: Region) -> str:
    return (
        f"CREATE PUBLICATION {publication_name(owner)} "
        f"FOR TABLE products WHERE (region = '{owner.region_id}');"
    )


def subscription_sql(owner: Region, subscriber: Region, fault: str) -> str:
    host, port = replication_endpoint(owner, subscriber, fault)
    name = application_name(owner, subscriber)
    return (
        f"CREATE SUBSCRIPTION {name} "
        f"CONNECTION 'host={host} port={port} dbname=appdb user=appuser password=apppass "
        f"application_name={name}' "
        f"PUBLICATION {publication_name(owner)} "
        f"WITH (copy_data = true, create_slot = true, enabled = true, slot_name = {name}, origin = none);"
    )


def synchronous_standby_sql(scenario_name: str, owner: Region) -> str:
    target = region_by_id(sync_target(scenario_name, owner.region_id))
    name = application_name(owner, target)
    return (
        f"ALTER SYSTEM SET synchronous_standby_names = 'ANY 1 ({name})';\n"
        "ALTER SYSTEM SET synchronous_commit = 'remote_apply';\n"
        "SELECT pg_reload_conf();"
    )


def reset_synchronous_sql() -> str:
    return (
        "ALTER SYSTEM SET synchronous_commit = 'on';\n"
        "ALTER SYSTEM RESET synchronous_standby_names;\n"
        "SELECT pg_reload_conf();"
    )


def region_code_in_name(name: str) -> str | None:
    for region in REGIONS:
        if f"-{region.code}-" in name:
            return region.code
    return None


def names_by_region_code(names: set[str]) -> dict[str, set[str]]:
    grouped = {region.code: set() for region in REGIONS}
    for name in names:
        code = region_code_in_name(name)
        if code in grouped:
            grouped[code].add(name)
    return grouped


def recovery_groups(present_names: set[str]) -> list[set[str]]:
    grouped = names_by_region_code(present_names)
    return [grouped[region.code] for region in REGIONS]


def regional_switchover_end_ms(
    attempts: list[Attempt],
    fault_ms: int,
    eligible_start_ms: int,
    groups: list[set[str]],
    recovery_timeout_ms: int,
) -> int:
    ends: list[int] = []
    for group in groups:
        recovered = first_recovered_write(attempts, eligible_start_ms, group)
        if recovered is None:
            ends.append(fault_ms + recovery_timeout_ms)
        else:
            ends.append(recovered.finished_ms)
    if not ends:
        return fault_ms + recovery_timeout_ms
    return max(ends)


def overlapping_write_errors(attempts: list[Attempt], fault_ms: int, end_ms: int) -> int:
    return sum(
        1
        for attempt in writes(attempts)
        if attempt.finished_ms >= fault_ms and attempt.started_ms <= end_ms and attempt.status != 201
    )


def overlapping_p99_ms(attempts: list[Attempt], fault_ms: int, end_ms: int) -> float | None:
    latencies = [
        attempt.latency_ms
        for attempt in writes(attempts)
        if attempt.finished_ms >= fault_ms and attempt.started_ms <= end_ms
    ]
    if not latencies:
        return None
    return round(nearest_rank(latencies, 99), 1)


def summed_duplicate_writes(counts_by_database: dict[str, dict[str, int]]) -> int:
    return sum(duplicate_writes(counts) for counts in counts_by_database.values())


def rejected_shard_writes(attempts: list[Attempt]) -> int:
    return sum(1 for attempt in writes(attempts) if attempt.status == 409)


def normalize_price(value: str) -> str:
    try:
        return f"{Decimal(value):.2f}"
    except (InvalidOperation, ValueError):
        return value


def convergence_lag_seconds(elapsed_seconds: float | None, limit_seconds: float) -> float:
    if elapsed_seconds is None:
        return round(float(limit_seconds) + 1, 3)
    return round(float(elapsed_seconds), 3)


def failover_verified(
    scenario: ScenarioConfig,
    statuses: dict[str, str],
    rto_seconds: float | None,
    proxies_disabled: bool,
) -> bool:
    if rto_seconds is None:
        return False
    killed = set(scenario.killed_regions)
    for region in REGIONS:
        database_status = statuses.get(region.container, "missing")
        app_status = statuses.get(region.app_container, "missing")
        if region.region_id in killed:
            if database_status not in {"exited", "dead"} or app_status not in {"exited", "dead"}:
                return False
        elif database_status != "running" or app_status != "running":
            return False
    if scenario.fault == "split-pairs" and not proxies_disabled:
        return False
    return True


def cross_application_names() -> tuple[str, ...]:
    names: list[str] = []
    for owner in REGIONS:
        for subscriber in REGIONS:
            if owner.region_id == subscriber.region_id:
                continue
            if SIDES[owner.region_id] != SIDES[subscriber.region_id]:
                names.append(application_name(owner, subscriber))
    return tuple(names)


def active_stack(root: Path, scenario: ScenarioConfig) -> ComposeStack:
    files = ["-f", "docker-compose.active-regions.yml"]
    if scenario.compose_overlay:
        files.extend(["-f", scenario.compose_overlay])
    return ComposeStack(root, scenario.compose_overlay, project_name=PROJECT_NAME, compose_files=files)


def run_active_scenario(
    root: Path,
    config: DrillConfig,
    scenario: ScenarioConfig,
    profile: str,
    commit_sha: str,
    run_url: str | None,
    keep: bool,
    output_dir: Path,
) -> dict:
    output_dir.mkdir(parents=True, exist_ok=True)
    stack = active_stack(root, scenario)
    try:
        return _execute_active(stack, config, scenario, profile, commit_sha, run_url, keep, output_dir)
    except Exception as exc:
        report = failure_report(config, commit_sha, run_url, str(exc))
        _annotate(report, scenario, profile)
        report["runtime"]["scenario"] = scenario.name
        report["runtime"]["failure_injection"] = scenario.fault
        report["runtime"]["measurement_scope"] = measurement_scope_for(scenario.name)
        log_path = output_dir / "scenarios" / scenario.name / "compose.log"
        if not log_path.exists():
            _preserve_logs(stack, log_path)
        if not keep:
            stack.down()
        return report


def _execute_active(
    stack: ComposeStack,
    config: DrillConfig,
    scenario: ScenarioConfig,
    profile: str,
    commit_sha: str,
    run_url: str | None,
    keep: bool,
    output_dir: Path,
) -> dict:
    _require_known_regions()
    stack.down()
    built = _ensure_images(stack)
    services = [region.postgres_service for region in REGIONS]
    services.extend(region.app_service for region in REGIONS)
    toxiproxy = _toxiproxy(config, scenario)
    if toxiproxy is not None:
        services = ["toxiproxy", *services]
    report: dict | None = None
    try:
        stack.up_services(services)
        timeout = ACTIVE_READY_TIMEOUT_SECONDS
        _wait_postgres(stack, timeout)
        _wait_apps(stack, timeout)
        _assert_home_writers(stack)
        if toxiproxy is not None:
            _prepare_proxies(stack, toxiproxy, timeout)
        iterations = scenario.iterations or 1
        runs = [
            _run_iteration(stack, config, scenario, toxiproxy, index)
            for index in range(1, iterations + 1)
        ]
        report = build_report(
            config,
            runs,
            commit_sha,
            run_url,
            _runtime(scenario, built),
            None,
            scenario_gates(config, scenario),
        )
        _annotate(report, scenario, profile)
    except Exception:
        _preserve_logs(stack, output_dir / "scenarios" / scenario.name / "compose.log")
        raise
    finally:
        if not keep:
            stack.down()
    if report is None:
        raise StackError(f"{scenario.name} did not produce a report")
    return report


def _run_iteration(
    stack: ComposeStack,
    config: DrillConfig,
    scenario: ScenarioConfig,
    toxiproxy: ToxiproxyClient | None,
    index: int,
) -> dict:
    print(f"{scenario.name} iteration {index}: preparing home-region replication", flush=True)
    timeout = ACTIVE_READY_TIMEOUT_SECONDS
    _start_databases(stack, timeout)
    _stop_compute(stack)
    _prepare_replication(stack, scenario)
    if scenario.fault == "split-pairs":
        _assert_proxies_enabled(toxiproxy)
    _enable_synchronous_apply(stack, scenario, config.replication_catchup_timeout_seconds)
    _catch_up(stack, scenario, index, config.replication_catchup_timeout_seconds)
    _start_apps(stack, timeout)
    _start_router(stack, timeout)
    proxies_disabled = False
    fault_injected_at = ""
    writer_switched_at = ""

    def inject_fault(control: WorkloadControl) -> None:
        nonlocal proxies_disabled, fault_injected_at, writer_switched_at
        fault_injected_at = utc_timestamp()
        control.mark_fault()
        if scenario.fault == "kill-regions":
            _kill_regions(stack, scenario)
        elif scenario.fault == "split-pairs":
            proxies_disabled = _partition_pairs(stack, toxiproxy, scenario, index, config.replication_catchup_timeout_seconds)
        else:
            raise StackError(f"unknown active-region fault {scenario.fault}")
        writer_switched_at = utc_timestamp()

    workload = _execute_regional_workload(
        run_index=index,
        warmup_seconds=config.warmup_seconds,
        recovery_timeout_seconds=config.recovery_timeout_seconds,
        write_interval_ms=config.write_interval_ms,
        client_timeout_seconds=config.client_timeout_seconds,
        inject_fault=inject_fault,
    )
    live = _live_regions(scenario)
    counts_by_database, rows_by_database = _snapshot(stack, live, index)
    present = {name for counts in counts_by_database.values() for name in counts}
    if scenario.fault == "split-pairs" and toxiproxy is not None:
        _enable_proxies(toxiproxy)
    lag = _measure_convergence(
        stack,
        live,
        index,
        scenario.convergence_lag_seconds_max,
        config.replication_catchup_timeout_seconds,
    )
    groups = recovery_groups(present)
    recovery_timeout_ms = int(config.recovery_timeout_seconds * 1000)
    rto = slowest_recovery_seconds(workload.attempts, workload.fault_ms, workload.eligible_start_ms, groups)
    rpo_seconds, lost = recovery_point(workload.attempts, workload.fault_ms, present)
    end_ms = regional_switchover_end_ms(
        workload.attempts,
        workload.fault_ms,
        workload.eligible_start_ms,
        groups,
        recovery_timeout_ms,
    )
    errors = overlapping_write_errors(workload.attempts, workload.fault_ms, end_ms)
    p99 = overlapping_p99_ms(workload.attempts, workload.fault_ms, end_ms)
    statuses = _statuses(stack)
    verified = failover_verified(scenario, statuses, rto, proxies_disabled)
    conflicts = write_conflicts(rows_by_database, rejected_shard_writes(workload.attempts))
    acknowledged = len({attempt.name for attempt in writes(workload.attempts) if attempt.status == 201})
    run = {
        "index": index,
        "rto_seconds": rto,
        "rpo_seconds": rpo_seconds,
        "rpo_lost_writes": lost,
        "duplicate_writes": summed_duplicate_writes(counts_by_database),
        "error_count": errors,
        "p99_latency_ms": p99,
        "write_conflicts": conflicts,
        "convergence_lag_seconds": lag,
        "acknowledged_writes": acknowledged,
        "read_error_count": 0,
        "driver_failover_verified": verified,
        "verification": "active-region-routing",
        "fault_injected_at": fault_injected_at,
        "writer_switched_at": writer_switched_at,
        "killed_regions": list(scenario.killed_regions),
        "proxies_disabled": proxies_disabled,
    }
    print(
        f"{scenario.name} iteration {index}: rto={rto}s rpo={rpo_seconds}s lost={lost} "
        f"duplicates={run['duplicate_writes']} conflicts={conflicts} lag={lag}s errors={errors} p99_ms={p99}",
        flush=True,
    )
    return run


def _prepare_replication(stack: ComposeStack, scenario: ScenarioConfig) -> None:
    for region in REGIONS:
        stack.psql(region.container, reset_synchronous_sql())
    for region in REGIONS:
        _drop_subscriptions(stack, region)
    for region in REGIONS:
        _drop_inactive_slots(stack, region)
        stack.psql(
            region.container,
            f"""
            DROP PUBLICATION IF EXISTS {publication_name(region)};
            TRUNCATE TABLE products RESTART IDENTITY;
            ALTER TABLE products REPLICA IDENTITY FULL;
            {sequence_sql(region)}
            {publication_sql(region)}
            """,
        )
    for owner in REGIONS:
        for subscriber in REGIONS:
            if owner.region_id == subscriber.region_id:
                continue
            stack.psql(subscriber.container, subscription_sql(owner, subscriber, scenario.fault))


def _drop_subscriptions(stack: ComposeStack, region: Region) -> None:
    raw = stack.psql(region.container, "SELECT subname FROM pg_subscription;")
    for name in [line.strip() for line in raw.splitlines() if line.strip()]:
        if not name.startswith("shard_"):
            continue
        stack.psql(
            region.container,
            f"""
            ALTER SUBSCRIPTION {name} DISABLE;
            ALTER SUBSCRIPTION {name} SET (slot_name = NONE);
            DROP SUBSCRIPTION {name};
            """,
        )


def _drop_inactive_slots(stack: ComposeStack, region: Region) -> None:
    stack.psql(
        region.container,
        """
        SELECT pg_drop_replication_slot(slot_name)
        FROM pg_replication_slots
        WHERE slot_name LIKE 'shard_%' AND active = false;
        """,
    )


def _enable_synchronous_apply(stack: ComposeStack, scenario: ScenarioConfig, timeout_seconds: float) -> None:
    for owner in REGIONS:
        target = region_by_id(sync_target(scenario.name, owner.region_id))
        name = application_name(owner, target)
        _wait_sync_state(stack, owner, name, {"async", "potential", "sync", "quorum"}, timeout_seconds)
        stack.psql(owner.container, synchronous_standby_sql(scenario.name, owner))
        _wait_sync_state(stack, owner, name, {"sync", "quorum"}, timeout_seconds)


def _wait_sync_state(
    stack: ComposeStack,
    owner: Region,
    application: str,
    accepted: set[str],
    timeout_seconds: float,
) -> None:
    deadline = time.monotonic() + timeout_seconds
    last_state = "no replication row"
    while time.monotonic() < deadline:
        rows = stack.psql(
            owner.container,
            "SELECT sync_state FROM pg_stat_replication "
            f"WHERE application_name = '{application}';",
        )
        states = [line.strip() for line in rows.splitlines() if line.strip()]
        if any(state in accepted for state in states):
            return
        last_state = rows or "no replication row"
        time.sleep(0.2)
    expected = ", ".join(sorted(accepted))
    raise StackError(
        f"{owner.region_id} standby {application} was not {expected} within {timeout_seconds}s ({last_state})"
    )


def _catch_up(stack: ComposeStack, scenario: ScenarioConfig, run_index: int, timeout_seconds: float) -> None:
    timeout_ms = max(1, int(timeout_seconds * 1000))
    tokens: list[tuple[Region, Region, str]] = []
    for owner in REGIONS:
        target = region_by_id(sync_target(scenario.name, owner.region_id))
        token = f"sync-{owner.code}-r{run_index}"
        tokens.append((owner, target, token))
        stack.psql(
            owner.container,
            f"""
            SET statement_timeout = '{timeout_ms}ms';
            INSERT INTO products (name, price, region)
            VALUES ('{token}', 1.00, '{owner.region_id}');
            """,
        )
    deadline = time.monotonic() + timeout_seconds
    pending = list(tokens)
    while pending and time.monotonic() < deadline:
        still_pending: list[tuple[Region, Region, str]] = []
        for owner, target, token in pending:
            count = stack.psql(target.container, f"SELECT count(*) FROM products WHERE name = '{token}';")
            if count != "1":
                still_pending.append((owner, target, token))
        pending = still_pending
        if pending:
            time.sleep(0.2)
    if pending:
        missing = ", ".join(token for _owner, _target, token in pending)
        raise StackError(f"synchronous apply did not catch up: {missing}")


def _kill_regions(stack: ComposeStack, scenario: ScenarioConfig) -> None:
    killed = [region_by_id(region_id) for region_id in scenario.killed_regions]
    for region in killed:
        print(f"{scenario.name}: killing {region.app_container} and {region.container}", flush=True)
        stack.kill(region.app_container)
        stack.kill(region.container)
    for region in killed:
        _wait_status(stack, region.app_container, {"exited", "dead"}, 30)
        _wait_status(stack, region.container, {"exited", "dead"}, 30)


def _partition_pairs(
    stack: ComposeStack,
    toxiproxy: ToxiproxyClient | None,
    scenario: ScenarioConfig,
    run_index: int,
    timeout_seconds: float,
) -> bool:
    if toxiproxy is None:
        raise StackError("partition-2-2 requires Toxiproxy")
    print(f"{scenario.name}: disabling cross-pair proxies", flush=True)
    for owner in REGIONS:
        toxiproxy.disable(proxy_name(owner))
    disabled = all(not toxiproxy.is_enabled(proxy_name(owner)) for owner in REGIONS)
    if not disabled:
        raise StackError("cross-pair proxies stayed enabled")
    _wait_cross_senders_gone(stack, timeout_seconds)
    timeout_ms = max(1, int(timeout_seconds * 1000))
    for owner in REGIONS:
        token = f"cut-{owner.code}-r{run_index}"
        stack.psql(
            owner.container,
            f"""
            SET statement_timeout = '{timeout_ms}ms';
            INSERT INTO products (name, price, region) VALUES ('{token}', 1.00, '{owner.region_id}');
            """,
        )
        for far in REGIONS:
            if SIDES[far.region_id] == SIDES[owner.region_id]:
                continue
            count = stack.psql(far.container, f"SELECT count(*) FROM products WHERE name = '{token}';")
            if count != "0":
                raise StackError(f"cut token {token} reached {far.region_id} while the pairs were split")
    return True


def _wait_cross_senders_gone(stack: ComposeStack, timeout_seconds: float) -> None:
    names = cross_application_names()
    quoted = ", ".join(f"'{name}'" for name in names)
    deadline = time.monotonic() + timeout_seconds
    last = "still streaming"
    while time.monotonic() < deadline:
        remaining = 0
        for region in REGIONS:
            count = stack.psql(
                region.container,
                f"SELECT count(*) FROM pg_stat_replication WHERE application_name IN ({quoted});",
            )
            remaining += int(count or "0")
        if remaining == 0:
            return
        last = str(remaining)
        time.sleep(0.3)
    raise StackError(f"cross-pair walsenders were still streaming after {timeout_seconds}s ({last})")


def _measure_convergence(
    stack: ComposeStack,
    live: tuple[Region, ...],
    run_index: int,
    limit_seconds: float,
    commit_timeout_seconds: float,
) -> float:
    if len(live) < 2:
        return 0.0
    timeout_ms = max(1, int(commit_timeout_seconds * 1000))
    started = time.monotonic()
    tokens: list[tuple[Region, str]] = []
    for region in live:
        token = f"conv-{region.code}-r{run_index}"
        tokens.append((region, token))
        stack.psql(
            region.container,
            f"""
            SET statement_timeout = '{timeout_ms}ms';
            INSERT INTO products (name, price, region)
            VALUES ('{token}', 1.00, '{region.region_id}');
            """,
        )
    if _converged(stack, live, tokens):
        return convergence_lag_seconds(time.monotonic() - started, limit_seconds)
    deadline = started + limit_seconds
    while time.monotonic() < deadline:
        if _converged(stack, live, tokens):
            return convergence_lag_seconds(time.monotonic() - started, limit_seconds)
        time.sleep(0.2)
    return convergence_lag_seconds(None, limit_seconds)


def _converged(stack: ComposeStack, live: tuple[Region, ...], tokens: list[tuple[Region, str]]) -> bool:
    for owner, token in tokens:
        for subscriber in live:
            if subscriber.region_id == owner.region_id:
                continue
            count = stack.psql(subscriber.container, f"SELECT count(*) FROM products WHERE name = '{token}';")
            if count != "1":
                return False
    return True


def _snapshot(
    stack: ComposeStack,
    live: tuple[Region, ...],
    run_index: int,
) -> tuple[dict[str, dict[str, int]], dict[str, list[tuple[str, str, str]]]]:
    counts_by_database: dict[str, dict[str, int]] = {}
    rows_by_database: dict[str, list[tuple[str, str, str]]] = {}
    pattern = f"drill-r{run_index}-%"
    for region in live:
        count_raw = stack.psql(
            region.container,
            "SELECT name || '|' || count(*)::text FROM products "
            f"WHERE name LIKE '{pattern}' GROUP BY name ORDER BY name;",
        )
        counts: dict[str, int] = {}
        for line in count_raw.splitlines():
            if "|" not in line:
                continue
            name, count = line.split("|", 1)
            counts[name] = int(count)
        counts_by_database[region.region_id] = counts
        row_raw = stack.psql(
            region.container,
            "SELECT name || '|' || region || '|' || price::text FROM products "
            f"WHERE name LIKE '{pattern}' ORDER BY name;",
        )
        rows: list[tuple[str, str, str]] = []
        for line in row_raw.splitlines():
            parts = line.split("|")
            if len(parts) != 3:
                continue
            rows.append((parts[0], parts[1], normalize_price(parts[2])))
        rows_by_database[region.region_id] = rows
    return counts_by_database, rows_by_database


def _execute_regional_workload(
    run_index: int,
    warmup_seconds: float,
    recovery_timeout_seconds: float,
    write_interval_ms: int,
    client_timeout_seconds: float,
    inject_fault,
):
    clock = Clock()
    control = WorkloadControl(clock)
    stop = threading.Event()
    lock = threading.Lock()
    attempts: list[Attempt] = []
    threads = [
        threading.Thread(
            target=_regional_writer,
            name=f"drill-writer-{region.code}",
            args=(region, run_index, write_interval_ms, client_timeout_seconds, clock, stop, lock, attempts, control),
            daemon=True,
        )
        for region in REGIONS
    ]
    for thread in threads:
        thread.start()
    time.sleep(warmup_seconds)
    warmup = [
        attempt
        for attempt in _snapshot_attempts(lock, attempts)
        if attempt.kind == "write" and attempt.status == 201
    ]
    warmed = {region_code_in_name(attempt.name) for attempt in warmup}
    if any(region.code not in warmed for region in REGIONS):
        stop.set()
        control.resume_writers()
        for thread in threads:
            thread.join(timeout=client_timeout_seconds + 1)
        missing = [region.region_id for region in REGIONS if region.code not in warmed]
        raise RuntimeError(f"warmup did not acknowledge a write for {', '.join(missing)}")
    entered_fault_ms = clock.ms()
    try:
        inject_fault(control)
        fault_ms = entered_fault_ms if control.fault_ms is None else control.fault_ms
        eligible_start_ms = clock.ms()
        _probe_regions(
            run_index,
            recovery_timeout_seconds,
            client_timeout_seconds,
            clock,
            lock,
            attempts,
            stop,
        )
    finally:
        stop.set()
        control.resume_writers()
        for thread in threads:
            thread.join(timeout=client_timeout_seconds + 1)
    return _Workload(list(attempts), fault_ms, eligible_start_ms)


@dataclass(frozen=True)
class _Workload:
    attempts: list[Attempt]
    fault_ms: int
    eligible_start_ms: int


def _regional_writer(
    region: Region,
    run_index: int,
    interval_ms: int,
    timeout_seconds: float,
    clock: Clock,
    stop: threading.Event,
    lock: threading.Lock,
    attempts: list[Attempt],
    control: WorkloadControl,
) -> None:
    sequence = 0
    while not stop.is_set():
        if not control.try_begin_write():
            if stop.wait(0.05):
                return
            continue
        name = f"drill-r{run_index}-{region.code}-w0-n{sequence:06d}"
        sequence += 1
        started = clock.ms()
        finished = started
        try:
            status = request_status(
                ROUTER_URL + "/api/products",
                "POST",
                {"name": name, "price": 1.0},
                timeout_seconds,
                {"X-Source-Region": region.region_id},
            )
            finished = clock.ms()
            with lock:
                attempts.append(Attempt("write", name, started, finished, status))
        finally:
            control.end_write()
        remaining = interval_ms - (finished - started)
        if remaining > 0 and stop.wait(remaining / 1000):
            return


def _probe_regions(
    run_index: int,
    recovery_timeout_seconds: float,
    timeout_seconds: float,
    clock: Clock,
    lock: threading.Lock,
    attempts: list[Attempt],
    stop: threading.Event,
) -> None:
    deadline = time.monotonic() + recovery_timeout_seconds
    threads = [
        threading.Thread(
            target=_probe_region,
            name=f"drill-probe-{region.code}",
            args=(region, run_index, deadline, timeout_seconds, clock, lock, attempts, stop),
            daemon=True,
        )
        for region in REGIONS
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        remaining = deadline - time.monotonic()
        thread.join(timeout=max(0.1, remaining + timeout_seconds))


def _probe_region(
    region: Region,
    run_index: int,
    deadline: float,
    timeout_seconds: float,
    clock: Clock,
    lock: threading.Lock,
    attempts: list[Attempt],
    stop: threading.Event,
) -> None:
    sequence = 0
    while time.monotonic() < deadline and not stop.is_set():
        name = f"drill-r{run_index}-{region.code}-p-n{sequence:06d}"
        sequence += 1
        started = clock.ms()
        status = request_status(
            ROUTER_URL + "/api/products",
            "POST",
            {"name": name, "price": 1.0},
            timeout_seconds,
            {"X-Source-Region": region.region_id},
        )
        finished = clock.ms()
        with lock:
            attempts.append(Attempt("write", name, started, finished, status))
        if status == 201:
            return
        if stop.wait(0.2):
            return


def _snapshot_attempts(lock: threading.Lock, attempts: list[Attempt]) -> list[Attempt]:
    with lock:
        return list(attempts)


def _ensure_images(stack: ComposeStack) -> bool:
    built = False
    if not stack._image_exists(APP_IMAGE):
        stack._compose("build", "app-us")
        built = True
    if not stack._image_exists(ROUTER_IMAGE):
        stack._compose("build", "servicetalk-router")
        built = True
    return built


def _toxiproxy(config: DrillConfig, scenario: ScenarioConfig) -> ToxiproxyClient | None:
    if scenario.fault != "split-pairs":
        return None
    return ToxiproxyClient(config.toxiproxy.api_url)


def _prepare_proxies(stack: ComposeStack, toxiproxy: ToxiproxyClient, timeout_seconds: float) -> None:
    stack.restart("multiregion-toxiproxy")
    toxiproxy.wait_ready(timeout_seconds)
    for owner in REGIONS:
        toxiproxy.ensure_proxy(
            proxy_name(owner),
            f"0.0.0.0:{PROXY_PORT[owner.region_id]}",
            f"{owner.host}:5432",
        )
        toxiproxy.enable(proxy_name(owner))
        toxiproxy.clear_toxics(proxy_name(owner))


def _assert_proxies_enabled(toxiproxy: ToxiproxyClient | None) -> None:
    if toxiproxy is None:
        raise StackError("partition-2-2 requires Toxiproxy")
    for owner in REGIONS:
        if not toxiproxy.is_enabled(proxy_name(owner)):
            raise StackError(f"{proxy_name(owner)} was not enabled before the workload")


def _enable_proxies(toxiproxy: ToxiproxyClient) -> None:
    for owner in REGIONS:
        toxiproxy.enable(proxy_name(owner))


def _wait_postgres(stack: ComposeStack, timeout_seconds: float) -> None:
    for region in REGIONS:
        stack.wait_postgres(region.container, timeout_seconds)


def _wait_apps(stack: ComposeStack, timeout_seconds: float) -> None:
    for region in REGIONS:
        stack.wait_http(f"http://127.0.0.1:{region.app_host_port}", timeout_seconds)


def _assert_home_writers(stack: ComposeStack) -> None:
    for region in REGIONS:
        mode = stack.psql(region.container, "SELECT pg_catalog.aurora_is_writer();")
        if mode != "t":
            raise StackError(f"{region.region_id} aurora_is_writer() is {mode}, expected t")


def _start_databases(stack: ComposeStack, timeout_seconds: float) -> None:
    for region in REGIONS:
        if stack.container_status(region.container) != "running":
            stack.start(region.container)
    _wait_postgres(stack, timeout_seconds)


def _stop_compute(stack: ComposeStack) -> None:
    stack.stop_named([region.app_container for region in REGIONS] + ["multiregion-router"])


def _start_apps(stack: ComposeStack, timeout_seconds: float) -> None:
    stack.start_named([region.app_container for region in REGIONS])
    _wait_apps(stack, timeout_seconds)


def _start_router(stack: ComposeStack, timeout_seconds: float) -> None:
    stack.up_services(["servicetalk-router"])
    stack.wait_http(ROUTER_URL, timeout_seconds)


def _live_regions(scenario: ScenarioConfig) -> tuple[Region, ...]:
    killed = set(scenario.killed_regions)
    return tuple(region for region in REGIONS if region.region_id not in killed)


def _statuses(stack: ComposeStack) -> dict[str, str]:
    statuses: dict[str, str] = {}
    for region in REGIONS:
        statuses[region.container] = stack.container_status(region.container)
        statuses[region.app_container] = stack.container_status(region.app_container)
    return statuses


def _wait_status(stack: ComposeStack, container: str, accepted: set[str], timeout_seconds: float) -> None:
    deadline = time.monotonic() + timeout_seconds
    last = "missing"
    while time.monotonic() < deadline:
        last = stack.container_status(container)
        if last in accepted:
            return
        time.sleep(0.2)
    raise StackError(f"{container} status {last} was not {sorted(accepted)}")


def _runtime(scenario: ScenarioConfig, built: bool) -> dict:
    return {
        "scenario": scenario.name,
        "failure_injection": scenario.fault,
        "topology": "active-regions",
        "regions": [region.region_id for region in REGIONS],
        "killed_regions": list(scenario.killed_regions),
        "workload_url": ROUTER_URL,
        "wrapper_dialect": "aurora-pg",
        "cluster_instance_host_pattern": "?:5432",
        "replication": "home-region-row-filter",
        "images_reused": not built,
        "application_promotion": "router pair receives new keys on its own home writer",
        "otel_agent": "not-attached",
        "measurement_scope": measurement_scope_for(scenario.name),
    }


def _annotate(report: dict, scenario: ScenarioConfig, profile: str) -> None:
    report["scenario"] = scenario.name
    report["profile"] = profile
    report["measurement_scope"] = measurement_scope_for(scenario.name)


def _preserve_logs(stack: ComposeStack, path: Path) -> None:
    try:
        stack.write_logs(path)
    except StackError:
        return


def _require_known_regions() -> None:
    found = tuple(region.region_id for region in REGIONS)
    if found != ACTIVE_REGION_IDS:
        raise StackError(f"active regions {found} do not match {ACTIVE_REGION_IDS}")

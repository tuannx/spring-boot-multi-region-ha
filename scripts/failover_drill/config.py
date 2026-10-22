from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

DISPLAY_METRICS: tuple[tuple[str, str, int], ...] = (
    ("rto_seconds", "RTO (s)", 3),
    ("rpo_seconds", "RPO (s)", 3),
    ("lost_acknowledged_writes", "Lost acknowledged writes", 0),
    ("duplicate_writes", "Duplicate writes", 0),
    ("error_count", "Errors during switchover", 0),
    ("p99_latency_ms", "p99 latency during switchover (ms)", 1),
)

METRIC_PLACES = {name: places for name, _label, places in DISPLAY_METRICS}


@dataclass(frozen=True)
class DrillGates:
    rto_seconds_max: float
    rpo_seconds_max: float
    regression_ratio_max: float
    regression_metrics: tuple[str, ...]


@dataclass(frozen=True)
class DrillConfig:
    iterations: int
    warmup_seconds: float
    recovery_timeout_seconds: float
    write_workers: int
    read_workers: int
    write_interval_ms: int
    read_interval_ms: int
    client_timeout_seconds: float
    stack_ready_timeout_seconds: float
    replication_catchup_timeout_seconds: float
    app_eu_url: str
    gates: DrillGates
    source: dict

    def to_dict(self) -> dict:
        return json.loads(json.dumps(self.source))


def load_config(path: Path) -> DrillConfig:
    payload = json.loads(path.read_text(encoding="utf-8"))
    expected = {
        "iterations",
        "warmup_seconds",
        "recovery_timeout_seconds",
        "write_workers",
        "read_workers",
        "write_interval_ms",
        "read_interval_ms",
        "client_timeout_seconds",
        "stack_ready_timeout_seconds",
        "replication_catchup_timeout_seconds",
        "app_eu_url",
        "gates",
    }
    unknown = set(payload) - expected
    if unknown:
        raise ValueError(f"Unknown failover drill config keys: {sorted(unknown)}")
    missing = expected - set(payload)
    if missing:
        raise ValueError(f"Missing failover drill config keys: {sorted(missing)}")

    gates_payload = payload["gates"]
    gate_keys = {
        "rto_seconds_max",
        "rpo_seconds_max",
        "regression_ratio_max",
        "regression_metrics",
    }
    unknown_gates = set(gates_payload) - gate_keys
    if unknown_gates:
        raise ValueError(f"Unknown failover drill gate keys: {sorted(unknown_gates)}")
    missing_gates = gate_keys - set(gates_payload)
    if missing_gates:
        raise ValueError(f"Missing failover drill gate keys: {sorted(missing_gates)}")

    regression_metrics = tuple(gates_payload["regression_metrics"])
    unknown_metrics = [name for name in regression_metrics if name not in METRIC_PLACES]
    if unknown_metrics:
        raise ValueError(f"Unknown regression metrics: {unknown_metrics}")

    iterations = int(payload["iterations"])
    if iterations < 1:
        raise ValueError("iterations must be at least 1")
    if int(payload["write_workers"]) < 1:
        raise ValueError("write_workers must be at least 1")

    gates = DrillGates(
        rto_seconds_max=float(gates_payload["rto_seconds_max"]),
        rpo_seconds_max=float(gates_payload["rpo_seconds_max"]),
        regression_ratio_max=float(gates_payload["regression_ratio_max"]),
        regression_metrics=regression_metrics,
    )
    return DrillConfig(
        iterations=iterations,
        warmup_seconds=float(payload["warmup_seconds"]),
        recovery_timeout_seconds=float(payload["recovery_timeout_seconds"]),
        write_workers=int(payload["write_workers"]),
        read_workers=int(payload["read_workers"]),
        write_interval_ms=int(payload["write_interval_ms"]),
        read_interval_ms=int(payload["read_interval_ms"]),
        client_timeout_seconds=float(payload["client_timeout_seconds"]),
        stack_ready_timeout_seconds=float(payload["stack_ready_timeout_seconds"]),
        replication_catchup_timeout_seconds=float(payload["replication_catchup_timeout_seconds"]),
        app_eu_url=str(payload["app_eu_url"]).rstrip("/"),
        gates=gates,
        source=payload,
    )

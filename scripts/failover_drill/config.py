from __future__ import annotations

import json
import re
from dataclasses import dataclass, replace
from pathlib import Path

PROFILES = ("pull-request", "nightly")
FAULTS = ("docker-kill-primary", "toxiproxy-partition", "kill-regions", "split-pairs")
TOPOLOGIES = ("single-writer", "active-regions")
SCENARIO_NAMES = (
    "primary-kill",
    "network-partition",
    "slow-db",
    "lose-1-of-4",
    "lose-2-of-4",
    "partition-2-2",
)
ACTIVE_REGION_IDS = ("us-east-1", "eu-west-1", "ap-southeast-1", "ap-northeast-1")
LOSE_ONE_REGIONS = ("ap-southeast-1",)
LOSE_TWO_REGIONS = ("eu-west-1", "ap-northeast-1")
_HOSTNAME = re.compile(r"[a-z0-9][a-z0-9.-]*")
_LISTEN = re.compile(r"0\.0\.0\.0:[0-9]+")

DISPLAY_METRICS: tuple[tuple[str, str, int], ...] = (
    ("rto_seconds", "RTO (s)", 3),
    ("rpo_seconds", "RPO (s)", 3),
    ("rpo_lost_writes", "RPO lost writes", 0),
    ("duplicate_writes", "Duplicate writes", 0),
    ("error_count", "Errors during switchover", 0),
    ("p99_latency_ms", "p99 latency during switchover (ms)", 1),
    ("write_conflicts", "Write conflicts", 0),
    ("convergence_lag_seconds", "Convergence lag (s)", 3),
)

METRIC_PLACES = {name: places for name, _label, places in DISPLAY_METRICS}
OPTIONAL_BASELINE_METRICS = frozenset({"write_conflicts", "convergence_lag_seconds"})


@dataclass(frozen=True)
class DrillGates:
    rto_seconds_max: float
    rpo_seconds_max: float
    rpo_lost_writes_max: int
    duplicate_writes_max: int
    regression_ratio_max: float
    rto_regression_min_delta_seconds: float
    rpo_regression_min_delta_seconds: float
    error_count_regression_min_delta: int
    regression_metrics: tuple[str, ...]
    write_conflicts_max: int
    convergence_lag_seconds_max: float

    def regression_min_deltas(self) -> dict[str, float]:
        return {
            "rto_seconds": self.rto_regression_min_delta_seconds,
            "rpo_seconds": self.rpo_regression_min_delta_seconds,
            "error_count": float(self.error_count_regression_min_delta),
        }


@dataclass(frozen=True)
class ToxiproxyConfig:
    image: str
    api_url: str
    proxy_name: str
    listen: str
    upstream: str


@dataclass(frozen=True)
class ScenarioConfig:
    name: str
    profiles: tuple[str, ...]
    fault: str
    replication_host: str
    compose_overlay: str | None
    latency_ms: int
    jitter_ms: int
    toxicity: float
    rpo_seconds_max: float
    rpo_lost_writes_max: int
    topology: str
    iterations: int | None
    killed_regions: tuple[str, ...]
    write_conflicts_max: int
    convergence_lag_seconds_max: float

    @property
    def uses_toxiproxy(self) -> bool:
        return self.topology == "single-writer" and self.compose_overlay is not None


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
    toxiproxy: ToxiproxyConfig
    scenarios: tuple[ScenarioConfig, ...]
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
        "toxiproxy",
        "scenarios",
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
        "rpo_lost_writes_max",
        "duplicate_writes_max",
        "regression_ratio_max",
        "rto_regression_min_delta_seconds",
        "rpo_regression_min_delta_seconds",
        "error_count_regression_min_delta",
        "regression_metrics",
        "write_conflicts_max",
        "convergence_lag_seconds_max",
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

    toxiproxy = _load_toxiproxy(payload["toxiproxy"])
    scenarios = _load_scenarios(payload["scenarios"])
    gates = DrillGates(
        rto_seconds_max=float(gates_payload["rto_seconds_max"]),
        rpo_seconds_max=float(gates_payload["rpo_seconds_max"]),
        rpo_lost_writes_max=int(gates_payload["rpo_lost_writes_max"]),
        duplicate_writes_max=int(gates_payload["duplicate_writes_max"]),
        regression_ratio_max=float(gates_payload["regression_ratio_max"]),
        rto_regression_min_delta_seconds=float(gates_payload["rto_regression_min_delta_seconds"]),
        rpo_regression_min_delta_seconds=float(gates_payload["rpo_regression_min_delta_seconds"]),
        error_count_regression_min_delta=int(gates_payload["error_count_regression_min_delta"]),
        regression_metrics=regression_metrics,
        write_conflicts_max=_require_int(gates_payload["write_conflicts_max"], "write_conflicts_max"),
        convergence_lag_seconds_max=float(gates_payload["convergence_lag_seconds_max"]),
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
        toxiproxy=toxiproxy,
        scenarios=scenarios,
        source=payload,
    )


def scenario_gates(config: DrillConfig, scenario: ScenarioConfig) -> DrillGates:
    return replace(
        config.gates,
        rpo_seconds_max=scenario.rpo_seconds_max,
        rpo_lost_writes_max=scenario.rpo_lost_writes_max,
        write_conflicts_max=scenario.write_conflicts_max,
        convergence_lag_seconds_max=scenario.convergence_lag_seconds_max,
    )


def select_scenarios(config: DrillConfig, profile: str) -> tuple[ScenarioConfig, ...]:
    if profile not in PROFILES:
        allowed = ", ".join(PROFILES)
        raise ValueError(f"Unknown failover drill profile {profile!r}; expected {allowed}")
    selected = tuple(scenario for scenario in config.scenarios if profile in scenario.profiles)
    if not selected:
        raise ValueError(f"Profile {profile} does not select a scenario")
    if profile == "pull-request":
        names = tuple(scenario.name for scenario in selected)
        if names != ("primary-kill",):
            raise ValueError(f"pull-request profile must run only primary-kill, got {list(names)}")
    return selected


def resolve_profile(value: str | None) -> str:
    profile = value or "pull-request"
    if profile not in PROFILES:
        allowed = ", ".join(PROFILES)
        raise ValueError(f"Unknown failover drill profile {profile!r}; expected {allowed}")
    return profile


def _load_toxiproxy(payload: object) -> ToxiproxyConfig:
    if not isinstance(payload, dict):
        raise ValueError("toxiproxy must be an object")
    keys = {"image", "api_url", "proxy_name", "listen", "upstream"}
    _require_exact_keys(payload, keys, "toxiproxy")
    image = str(payload["image"])
    if ":" not in image or image.endswith(":latest"):
        raise ValueError("toxiproxy image must be pinned to an immutable tag")
    listen = str(payload["listen"])
    upstream = str(payload["upstream"])
    if _LISTEN.fullmatch(listen) is None:
        raise ValueError(f"toxiproxy listen must be 0.0.0.0:<port>, got {listen}")
    if not upstream.endswith(":5432") or _HOSTNAME.fullmatch(upstream.split(":", 1)[0]) is None:
        raise ValueError(f"toxiproxy upstream is not a pinned host:5432, got {upstream}")
    api_url = str(payload["api_url"]).rstrip("/")
    if not api_url.startswith("http://127.0.0.1:"):
        raise ValueError("toxiproxy api_url must be an IPv4 loopback URL")
    return ToxiproxyConfig(
        image=image,
        api_url=api_url,
        proxy_name=str(payload["proxy_name"]),
        listen=listen,
        upstream=upstream,
    )


def _load_scenarios(payload: object) -> tuple[ScenarioConfig, ...]:
    if not isinstance(payload, list) or not payload:
        raise ValueError("scenarios must be a non-empty list in run order")
    scenarios: list[ScenarioConfig] = []
    for entry in payload:
        if not isinstance(entry, dict):
            raise ValueError("each scenario must be an object")
        keys = {
            "name",
            "profiles",
            "fault",
            "replication_host",
            "compose_overlay",
            "latency_ms",
            "jitter_ms",
            "toxicity",
            "rpo_seconds_max",
            "rpo_lost_writes_max",
            "topology",
            "iterations",
            "killed_regions",
            "write_conflicts_max",
            "convergence_lag_seconds_max",
        }
        _require_exact_keys(entry, keys, "scenario")
        name = str(entry["name"])
        if name not in SCENARIO_NAMES:
            raise ValueError(f"Unknown scenario {name}")
        profiles = tuple(entry["profiles"])
        if not profiles or any(profile not in PROFILES for profile in profiles):
            raise ValueError(f"Scenario {name} has profiles outside {list(PROFILES)}")
        fault = str(entry["fault"])
        if fault not in FAULTS:
            raise ValueError(f"Scenario {name} has unknown fault {fault}")
        host = str(entry["replication_host"])
        if _HOSTNAME.fullmatch(host) is None:
            raise ValueError(f"Scenario {name} has an invalid replication host")
        overlay = entry["compose_overlay"]
        if overlay is not None:
            overlay = str(overlay)
            if "/" in overlay or not overlay.endswith(".yml"):
                raise ValueError(f"Scenario {name} compose overlay must be a single yml file name")
        latency_ms = _require_int(entry["latency_ms"], f"{name} latency_ms")
        jitter_ms = _require_int(entry["jitter_ms"], f"{name} jitter_ms")
        if jitter_ms != 0:
            raise ValueError(f"Scenario {name} jitter_ms must be 0 so the slow-db delay is deterministic")
        if type(entry["toxicity"]) not in (int, float) or bool(entry["toxicity"]) is entry["toxicity"]:
            raise ValueError(f"Scenario {name} toxicity must be a number")
        toxicity = float(entry["toxicity"])
        if toxicity != 1.0:
            raise ValueError(f"Scenario {name} toxicity must be 1 so every connection takes the toxic")
        if latency_ms < 0:
            raise ValueError(f"Scenario {name} latency_ms must be >= 0")
        if name == "slow-db" and latency_ms <= 0:
            raise ValueError("slow-db latency_ms must be a positive fixed delay")
        if name != "slow-db" and latency_ms != 0:
            raise ValueError(f"Scenario {name} must not add latency")
        rpo_seconds_max = float(entry["rpo_seconds_max"])
        if type(entry["rpo_seconds_max"]) not in (int, float) or isinstance(entry["rpo_seconds_max"], bool):
            raise ValueError(f"Scenario {name} rpo_seconds_max must be a number")
        if rpo_seconds_max < 0:
            raise ValueError(f"Scenario {name} rpo_seconds_max must be >= 0")
        lost_max = _require_int(entry["rpo_lost_writes_max"], f"{name} rpo_lost_writes_max")
        if name == "slow-db":
            if lost_max <= 0:
                raise ValueError("slow-db rpo_lost_writes_max must be above zero")
        elif lost_max != 0 or rpo_seconds_max != 0:
            raise ValueError(f"Scenario {name} rpo_seconds_max and rpo_lost_writes_max must be 0")
        topology = str(entry["topology"])
        if topology not in TOPOLOGIES:
            raise ValueError(f"Scenario {name} has unknown topology {topology}")
        iterations = _optional_iterations(entry["iterations"], name)
        killed_regions = _region_tuple(entry["killed_regions"], name)
        conflicts_max = _require_int(entry["write_conflicts_max"], f"{name} write_conflicts_max")
        if conflicts_max < 0:
            raise ValueError(f"Scenario {name} write_conflicts_max must be >= 0")
        lag_max = _require_number(entry["convergence_lag_seconds_max"], f"{name} convergence_lag_seconds_max")
        if lag_max < 0:
            raise ValueError(f"Scenario {name} convergence_lag_seconds_max must be >= 0")
        if name == "network-partition" and fault != "toxiproxy-partition":
            raise ValueError("network-partition must disable the Toxiproxy cross-region proxy")
        if name == "primary-kill" and (fault != "docker-kill-primary" or overlay is not None):
            raise ValueError("primary-kill must kill the primary container without a Toxiproxy overlay")
        if topology == "single-writer" and name != "primary-kill" and overlay is None:
            raise ValueError(f"Scenario {name} requires the Toxiproxy compose overlay")
        if topology == "active-regions":
            _validate_active_scenario(name, profiles, fault, overlay, iterations, killed_regions, lost_max, rpo_seconds_max, conflicts_max, lag_max)
        elif killed_regions:
            raise ValueError(f"Scenario {name} does not kill a home region")
        if "pull-request" in profiles and name != "primary-kill":
            raise ValueError(f"Scenario {name} is nightly-only")
        scenarios.append(
            ScenarioConfig(
                name=name,
                profiles=profiles,
                fault=fault,
                replication_host=host,
                compose_overlay=overlay,
                latency_ms=latency_ms,
                jitter_ms=jitter_ms,
                toxicity=toxicity,
                rpo_seconds_max=rpo_seconds_max,
                rpo_lost_writes_max=lost_max,
                topology=topology,
                iterations=iterations,
                killed_regions=killed_regions,
                write_conflicts_max=conflicts_max,
                convergence_lag_seconds_max=lag_max,
            )
        )
    names = tuple(scenario.name for scenario in scenarios)
    if names != SCENARIO_NAMES:
        raise ValueError(f"scenarios must be {list(SCENARIO_NAMES)} in that order")
    single_writer_overlays = {
        scenario.compose_overlay
        for scenario in scenarios
        if scenario.topology == "single-writer" and scenario.compose_overlay
    }
    if len(single_writer_overlays) != 1:
        raise ValueError("extended scenarios must share one compose overlay")
    return tuple(scenarios)


def _validate_active_scenario(
    name: str,
    profiles: tuple[str, ...],
    fault: str,
    overlay: str | None,
    iterations: int | None,
    killed_regions: tuple[str, ...],
    lost_max: int,
    rpo_seconds_max: float,
    conflicts_max: int,
    lag_max: float,
) -> None:
    if profiles != ("nightly",):
        raise ValueError(f"Scenario {name} is nightly-only")
    if iterations != 3:
        raise ValueError(f"Scenario {name} runs three iterations, the same count as the single-writer drill")
    if lost_max != 0 or rpo_seconds_max != 0 or conflicts_max != 0:
        raise ValueError(f"Scenario {name} keeps zero lost writes, zero RPO seconds, and zero write conflicts")
    if lag_max <= 0:
        raise ValueError(f"Scenario {name} convergence_lag_seconds_max must be above zero")
    if name == "lose-1-of-4" and (fault != "kill-regions" or killed_regions != LOSE_ONE_REGIONS or overlay is not None):
        raise ValueError("lose-1-of-4 kills ap-southeast-1 without a Toxiproxy overlay")
    if name == "lose-2-of-4" and (fault != "kill-regions" or killed_regions != LOSE_TWO_REGIONS or overlay is not None):
        raise ValueError("lose-2-of-4 kills eu-west-1 and ap-northeast-1 without a Toxiproxy overlay")
    if name == "partition-2-2" and (
        fault != "split-pairs" or killed_regions or overlay != "docker-compose.active-regions-partition.yml"
    ):
        raise ValueError("partition-2-2 splits west and east through the active-region Toxiproxy overlay")
    if name not in {"lose-1-of-4", "lose-2-of-4", "partition-2-2"}:
        raise ValueError(f"Scenario {name} is not an active-region scenario")


def _optional_iterations(value: object, name: str) -> int | None:
    if value is None:
        return None
    parsed = _require_int(value, f"{name} iterations")
    if parsed < 1:
        raise ValueError(f"{name} iterations must be at least 1")
    return parsed


def _region_tuple(value: object, name: str) -> tuple[str, ...]:
    if not isinstance(value, list):
        raise ValueError(f"{name} killed_regions must be a list")
    regions = tuple(str(region) for region in value)
    if any(region not in ACTIVE_REGION_IDS for region in regions):
        raise ValueError(f"{name} killed_regions must be a subset of {list(ACTIVE_REGION_IDS)}")
    if len(regions) != len(set(regions)):
        raise ValueError(f"{name} killed_regions must not repeat a region")
    return regions


def _require_number(value: object, name: str) -> float:
    if type(value) not in (int, float) or isinstance(value, bool):
        raise ValueError(f"{name} must be a number")
    return float(value)


def _require_exact_keys(payload: dict, keys: set[str], label: str) -> None:
    unknown = set(payload) - keys
    if unknown:
        raise ValueError(f"Unknown {label} keys: {sorted(unknown)}")
    missing = keys - set(payload)
    if missing:
        raise ValueError(f"Missing {label} keys: {sorted(missing)}")


def _require_int(value: object, name: str) -> int:
    if type(value) is not int:
        raise ValueError(f"{name} must be an integer")
    return value

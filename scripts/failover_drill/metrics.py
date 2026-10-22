from __future__ import annotations

import math
import statistics
from dataclasses import dataclass

from failover_drill.config import METRIC_PLACES, DrillGates


@dataclass(frozen=True)
class Attempt:
    kind: str
    name: str
    started_ms: int
    finished_ms: int
    status: int | None

    @property
    def latency_ms(self) -> int:
        return self.finished_ms - self.started_ms


def writes(attempts: list[Attempt]) -> list[Attempt]:
    return [attempt for attempt in attempts if attempt.kind == "write"]


def first_recovered_write(
    attempts: list[Attempt],
    eligible_start_ms: int,
    present_names: set[str],
) -> Attempt | None:
    candidates = [
        attempt
        for attempt in writes(attempts)
        if attempt.status == 201
        and attempt.started_ms >= eligible_start_ms
        and attempt.name in present_names
    ]
    if not candidates:
        return None
    return min(candidates, key=lambda attempt: attempt.finished_ms)


def recovery_time_seconds(
    attempts: list[Attempt],
    fault_ms: int,
    eligible_start_ms: int,
    present_names: set[str],
) -> float | None:
    recovered = first_recovered_write(attempts, eligible_start_ms, present_names)
    if recovered is None:
        return None
    return round((recovered.finished_ms - fault_ms) / 1000, 3)


def recovery_point(
    attempts: list[Attempt],
    fault_ms: int,
    present_names: set[str],
) -> tuple[float, int]:
    lost_names = {
        attempt.name
        for attempt in writes(attempts)
        if attempt.status == 201 and attempt.name not in present_names
    }
    if not lost_names:
        return 0.0, 0
    oldest_ack_ms = min(
        attempt.finished_ms
        for attempt in writes(attempts)
        if attempt.status == 201 and attempt.name in lost_names
    )
    return round(max(0, fault_ms - oldest_ack_ms) / 1000, 3), len(lost_names)


def duplicate_writes(name_counts: dict[str, int]) -> int:
    return sum(count - 1 for count in name_counts.values() if count > 1)


def window_end_ms(
    attempts: list[Attempt],
    fault_ms: int,
    eligible_start_ms: int,
    present_names: set[str],
    recovery_timeout_ms: int,
) -> int:
    recovered = first_recovered_write(attempts, eligible_start_ms, present_names)
    if recovered is None:
        return fault_ms + recovery_timeout_ms
    return recovered.finished_ms


def _in_switchover(attempt: Attempt, fault_ms: int, end_ms: int) -> bool:
    return attempt.finished_ms >= fault_ms and attempt.started_ms <= end_ms


def switchover_error_count(
    attempts: list[Attempt],
    fault_ms: int,
    eligible_start_ms: int,
    present_names: set[str],
    recovery_timeout_ms: int,
) -> int:
    end_ms = window_end_ms(attempts, fault_ms, eligible_start_ms, present_names, recovery_timeout_ms)
    return sum(
        1
        for attempt in writes(attempts)
        if _in_switchover(attempt, fault_ms, end_ms) and attempt.status != 201
    )


def switchover_p99_ms(
    attempts: list[Attempt],
    fault_ms: int,
    eligible_start_ms: int,
    present_names: set[str],
    recovery_timeout_ms: int,
) -> float | None:
    end_ms = window_end_ms(attempts, fault_ms, eligible_start_ms, present_names, recovery_timeout_ms)
    latencies = [
        attempt.latency_ms
        for attempt in writes(attempts)
        if _in_switchover(attempt, fault_ms, end_ms)
    ]
    if not latencies:
        return None
    return round(nearest_rank(latencies, 99), 1)


def read_error_count(
    attempts: list[Attempt],
    fault_ms: int,
    eligible_start_ms: int,
    present_names: set[str],
    recovery_timeout_ms: int,
) -> int:
    end_ms = window_end_ms(attempts, fault_ms, eligible_start_ms, present_names, recovery_timeout_ms)
    return sum(
        1
        for attempt in attempts
        if attempt.kind == "read"
        and _in_switchover(attempt, fault_ms, end_ms)
        and attempt.status != 200
    )


def nearest_rank(values: list[int], percentile: float) -> float:
    ordered = sorted(values)
    rank = max(1, math.ceil(percentile / 100 * len(ordered)))
    return float(ordered[min(len(ordered), rank) - 1])


def median_metric(values: list[float | int | None], places: int) -> float | int | None:
    if not values or any(value is None for value in values):
        return None
    raw = statistics.median([float(value) for value in values if value is not None])
    if places == 0:
        return int(round(raw))
    return round(raw, places)


def _scaled(value: float, places: int) -> int:
    return int(round(float(value) * (10 ** places)))


def regression(current: float, baseline: float, places: int, ratio_max: float) -> tuple[bool, float | None]:
    current_i = _scaled(current, places)
    baseline_i = _scaled(baseline, places)
    if baseline_i == 0:
        return current_i > 0, None
    limit = int(round(ratio_max * 10000))
    regressed = current_i * 10000 > baseline_i * (10000 + limit)
    ratio = (current_i - baseline_i) / baseline_i
    return regressed, ratio


def direction(current: float, baseline: float) -> str:
    if current == baseline:
        return "flat"
    if current > baseline:
        return "up"
    return "down"


def metric_delta(name: str, current: float | None, baseline: float | None, ratio_max: float) -> dict | None:
    if current is None or baseline is None:
        return None
    places = METRIC_PLACES[name]
    regressed, ratio = regression(current, baseline, places, ratio_max)
    absolute = current - baseline
    if places == 0:
        absolute_value: float | int = int(round(absolute))
    else:
        absolute_value = round(absolute, places)
    return {
        "absolute": absolute_value,
        "ratio": None if ratio is None else round(ratio, 4),
        "unbounded": ratio is None and current > baseline,
        "direction": direction(current, baseline),
        "regressed": regressed,
    }


def evaluate_gate(
    aggregate: dict,
    baseline_aggregate: dict | None,
    gates: DrillGates,
    runs: list[dict],
) -> dict:
    failures: list[str] = []
    for run in runs:
        if run.get("rto_seconds") is None:
            failures.append(f"run {run['index']} produced no recovered write")
        if not run.get("driver_failover_verified"):
            failures.append(f"run {run['index']} did not verify driver failover onto postgres-eu")

    rto = aggregate.get("rto_seconds")
    rpo = aggregate.get("rpo_seconds")
    if rto is None or rto > gates.rto_seconds_max:
        failures.append(
            f"RTO {rto}s exceeds {gates.rto_seconds_max}s"
            if rto is not None
            else f"RTO is missing and the limit is {gates.rto_seconds_max}s"
        )
    if rpo is None or rpo > gates.rpo_seconds_max:
        failures.append(
            f"RPO {rpo}s exceeds {gates.rpo_seconds_max}s"
            if rpo is not None
            else "RPO is missing"
        )

    if baseline_aggregate is not None:
        for name in gates.regression_metrics:
            current = aggregate.get(name)
            baseline = baseline_aggregate.get(name)
            if current is None or baseline is None:
                failures.append(f"{name} cannot be compared with the main baseline")
                continue
            regressed, ratio = regression(current, baseline, METRIC_PLACES[name], gates.regression_ratio_max)
            if regressed:
                if ratio is None:
                    failures.append(f"{name} regressed from 0 to {current}")
                else:
                    failures.append(
                        f"{name} regressed {ratio * 100:.1f}% "
                        f"(limit {gates.regression_ratio_max * 100:.0f}%)"
                    )

    unique: list[str] = []
    for failure in failures:
        if failure not in unique:
            unique.append(failure)
    return {"passed": not unique, "failures": unique}

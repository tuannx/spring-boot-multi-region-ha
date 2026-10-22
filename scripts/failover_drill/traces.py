from __future__ import annotations

import json
from datetime import datetime, timezone


def utc_timestamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def uncovered_iterations(payload: str, runs: list[dict]) -> list[int]:
    spans = list(_iter_spans(payload))
    missing: list[int] = []
    for run in runs:
        fault_at = run.get("fault_injected_at")
        switched_at = run.get("writer_switched_at")
        index = int(run.get("index") or 0)
        if not isinstance(fault_at, str) or not isinstance(switched_at, str):
            missing.append(index)
            continue
        if not _covers(spans, fault_at, switched_at):
            missing.append(index)
    return missing


def _covers(spans: list[tuple[int, int]], fault_at: str, switched_at: str) -> bool:
    fault_ns = _iso_to_unix_nano(fault_at)
    switch_ns = _iso_to_unix_nano(switched_at)
    before = any(end < fault_ns for _start, end in spans)
    during = any(start <= switch_ns and end >= fault_ns for start, end in spans)
    after = any(start > switch_ns for start, _end in spans)
    return before and during and after


def _iter_spans(payload: str):
    for document in _documents(payload):
        for resource in document.get("resourceSpans") or []:
            for scope in resource.get("scopeSpans") or []:
                for span in scope.get("spans") or []:
                    start = _nano(span.get("startTimeUnixNano"))
                    end = _nano(span.get("endTimeUnixNano"))
                    if start is None or end is None:
                        continue
                    yield start, end


def _documents(payload: str):
    text = payload.strip()
    if not text:
        return
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        parsed = None
    if isinstance(parsed, dict):
        yield parsed
        return
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        document = json.loads(line)
        if isinstance(document, dict):
            yield document


def _nano(value) -> int | None:
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.isdigit():
        return int(value)
    return None


def _iso_to_unix_nano(value: str) -> int:
    parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
    return int(parsed.timestamp() * 1_000_000_000)

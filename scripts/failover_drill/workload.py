from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass

from failover_drill.metrics import Attempt


@dataclass(frozen=True)
class WorkloadResult:
    attempts: list[Attempt]
    fault_ms: int
    eligible_start_ms: int


class Clock:
    def __init__(self) -> None:
        self._origin = time.monotonic()

    def ms(self) -> int:
        return int((time.monotonic() - self._origin) * 1000)


class WorkloadControl:
    def __init__(self, clock: Clock) -> None:
        self._clock = clock
        self._pause = threading.Event()
        self._lock = threading.Lock()
        self._idle = threading.Condition(self._lock)
        self._in_flight = 0
        self.fault_ms: int | None = None

    def now_ms(self) -> int:
        return self._clock.ms()

    def pause_writers(self) -> None:
        self._pause.set()

    def resume_writers(self) -> None:
        self._pause.clear()

    def try_begin_write(self) -> bool:
        with self._lock:
            if self._pause.is_set():
                return False
            self._in_flight += 1
            return True

    def end_write(self) -> None:
        with self._idle:
            self._in_flight -= 1
            if self._in_flight == 0:
                self._idle.notify_all()

    def wait_until_idle(self, timeout_seconds: float) -> None:
        deadline = time.monotonic() + timeout_seconds
        with self._idle:
            while self._in_flight > 0:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError(
                        f"workload writers were still in flight after {timeout_seconds}s"
                    )
                self._idle.wait(remaining)

    def mark_fault(self) -> None:
        if self.fault_ms is None:
            self.fault_ms = self._clock.ms()


def execute_workload(
    base_url: str,
    run_index: int,
    warmup_seconds: float,
    recovery_timeout_seconds: float,
    write_workers: int,
    read_workers: int,
    write_interval_ms: int,
    read_interval_ms: int,
    client_timeout_seconds: float,
    inject_fault,
) -> WorkloadResult:
    clock = Clock()
    control = WorkloadControl(clock)
    stop = threading.Event()
    lock = threading.Lock()
    attempts: list[Attempt] = []
    threads = [
        threading.Thread(
            target=_writer,
            name=f"drill-writer-{worker}",
            args=(
                worker,
                run_index,
                base_url,
                write_interval_ms,
                client_timeout_seconds,
                write_workers,
                clock,
                stop,
                lock,
                attempts,
                control,
            ),
            daemon=True,
        )
        for worker in range(write_workers)
    ]
    threads.extend(
        threading.Thread(
            target=_reader,
            name=f"drill-reader-{worker}",
            args=(
                worker,
                base_url,
                read_interval_ms,
                client_timeout_seconds,
                read_workers,
                clock,
                stop,
                lock,
                attempts,
            ),
            daemon=True,
        )
        for worker in range(read_workers)
    )
    for thread in threads:
        thread.start()

    time.sleep(warmup_seconds)
    warmup_writes = [
        attempt for attempt in _snapshot(lock, attempts)
        if attempt.kind == "write" and attempt.status == 201
    ]
    if len(warmup_writes) < write_workers:
        stop.set()
        for thread in threads:
            thread.join(timeout=client_timeout_seconds + 1)
        raise RuntimeError(
            f"warmup produced {len(warmup_writes)} acknowledged writes, expected at least {write_workers}"
        )

    entered_fault_ms = clock.ms()
    try:
        inject_fault(control)
        fault_ms = entered_fault_ms if control.fault_ms is None else control.fault_ms
        eligible_start_ms = clock.ms()
        deadline = time.monotonic() + recovery_timeout_seconds
        probe = 0
        while time.monotonic() < deadline:
            name = f"drill-r{run_index}-wP-n{probe:06d}"
            probe += 1
            started = clock.ms()
            status = _request(
                base_url + "/api/products",
                "POST",
                {"name": name, "price": 1.0},
                client_timeout_seconds,
            )
            finished = clock.ms()
            with lock:
                attempts.append(Attempt("write", name, started, finished, status))
            if status == 201:
                time.sleep(0.5)
                break
    finally:
        stop.set()
        control.resume_writers()
        for thread in threads:
            thread.join(timeout=client_timeout_seconds + 1)
    return WorkloadResult(
        attempts=list(attempts),
        fault_ms=fault_ms,
        eligible_start_ms=eligible_start_ms,
    )


def _snapshot(lock: threading.Lock, attempts: list[Attempt]) -> list[Attempt]:
    with lock:
        return list(attempts)


def _writer(
    worker: int,
    run_index: int,
    base_url: str,
    interval_ms: int,
    timeout_seconds: float,
    workers: int,
    clock: Clock,
    stop: threading.Event,
    lock: threading.Lock,
    attempts: list[Attempt],
    control: WorkloadControl,
) -> None:
    time.sleep(worker * (interval_ms / workers) / 1000)
    sequence = 0
    while not stop.is_set():
        if not control.try_begin_write():
            if stop.wait(0.05):
                return
            continue
        name = f"drill-r{run_index}-w{worker}-n{sequence:06d}"
        sequence += 1
        try:
            started = clock.ms()
            status = _request(
                base_url + "/api/products",
                "POST",
                {"name": name, "price": 1.0},
                timeout_seconds,
            )
            finished = clock.ms()
            with lock:
                attempts.append(Attempt("write", name, started, finished, status))
        finally:
            control.end_write()
        remaining = interval_ms - (finished - started)
        if remaining > 0 and stop.wait(remaining / 1000):
            return


def _reader(
    worker: int,
    base_url: str,
    interval_ms: int,
    timeout_seconds: float,
    workers: int,
    clock: Clock,
    stop: threading.Event,
    lock: threading.Lock,
    attempts: list[Attempt],
) -> None:
    time.sleep(worker * (interval_ms / max(workers, 1)) / 1000)
    sequence = 0
    while not stop.is_set():
        sequence += 1
        started = clock.ms()
        status = _request(base_url + "/api/products", "GET", None, timeout_seconds)
        finished = clock.ms()
        with lock:
            attempts.append(Attempt("read", f"read-{worker}-{sequence}", started, finished, status))
        remaining = interval_ms - (finished - started)
        if remaining > 0 and stop.wait(remaining / 1000):
            return


def _request(url: str, method: str, body: dict | None, timeout_seconds: float) -> int | None:
    payload = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=payload, method=method)
    request.add_header("Connection", "close")
    if payload is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
            response.read()
            return response.status
    except urllib.error.HTTPError as exc:
        exc.read()
        return exc.code
    except (urllib.error.URLError, TimeoutError, ConnectionError, OSError):
        return None

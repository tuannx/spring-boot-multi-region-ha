from __future__ import annotations

import json
import unittest

from failover_drill.traces import uncovered_iterations, utc_timestamp


FAULT = "2026-10-22T05:30:01.000Z"
SWITCH = "2026-10-22T05:30:04.000Z"


def _span(start_iso: str, end_iso: str) -> dict:
    return {
        "startTimeUnixNano": str(_iso_nano(start_iso)),
        "endTimeUnixNano": str(_iso_nano(end_iso)),
    }


def _iso_nano(value: str) -> int:
    from datetime import datetime, timezone

    parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
    return int(parsed.timestamp() * 1_000_000_000)


def _payload(*spans: dict) -> str:
    return json.dumps({"resourceSpans": [{"scopeSpans": [{"spans": list(spans)}]}]})


class TraceCoverageTest(unittest.TestCase):
    def test_ndjson_covers_before_during_and_after_the_writer_switch(self) -> None:
        first = _payload(_span("2026-10-22T05:29:58.000Z", "2026-10-22T05:29:59.000Z"))
        second = _payload(
            _span("2026-10-22T05:30:00.500Z", "2026-10-22T05:30:03.000Z"),
            _span("2026-10-22T05:30:05.000Z", "2026-10-22T05:30:06.000Z"),
        )
        missing = uncovered_iterations(
            first + "\n" + second + "\n",
            [{"index": 1, "fault_injected_at": FAULT, "writer_switched_at": SWITCH}],
        )
        self.assertEqual(missing, [])

    def test_a_single_span_that_crosses_the_window_does_not_count_as_coverage(self) -> None:
        payload = _payload(_span("2026-10-22T05:29:58.000Z", "2026-10-22T05:30:06.000Z"))
        missing = uncovered_iterations(
            payload,
            [{"index": 2, "fault_injected_at": FAULT, "writer_switched_at": SWITCH}],
        )
        self.assertEqual(missing, [2])

    def test_a_run_without_timestamps_is_uncovered(self) -> None:
        payload = _payload(_span("2026-10-22T05:29:58.000Z", "2026-10-22T05:29:59.000Z"))
        missing = uncovered_iterations(payload, [{"index": 3}])
        self.assertEqual(missing, [3])

    def test_utc_timestamp_has_millisecond_precision(self) -> None:
        value = utc_timestamp()
        self.assertRegex(value, r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$")


if __name__ == "__main__":
    unittest.main()

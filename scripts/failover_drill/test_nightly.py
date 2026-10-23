from __future__ import annotations

import json
import re
import tempfile
import unittest
from pathlib import Path

from failover_drill.config import load_config, resolve_profile, select_scenarios
from failover_drill.nightly_issue import MARKER, TITLE, plan_tracking_issue, render_issue_body
from failover_drill.nightly_report import combine_scenario_reports, render_nightly_markdown, should_publish_baseline
from failover_drill.replication import subscription_sql
from failover_drill.report import NIGHTLY_MEASUREMENT_SCOPE, render_comment
from failover_drill.toxiproxy import latency_toxic_body, proxy_body

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / "config" / "failover-drill.json"


class NightlyConfigTest(unittest.TestCase):
    def test_repository_config_is_deterministic_and_nightly_only_for_extended_scenarios(self) -> None:
        config = load_config(CONFIG)
        self.assertEqual(resolve_profile(None), "pull-request")
        self.assertEqual(
            tuple(scenario.name for scenario in select_scenarios(config, "pull-request")),
            ("primary-kill",),
        )
        self.assertEqual(
            tuple(scenario.name for scenario in select_scenarios(config, "nightly")),
            ("primary-kill", "network-partition", "slow-db"),
        )
        primary, partition, slow = config.scenarios
        self.assertIsNone(primary.compose_overlay)
        self.assertEqual(primary.fault, "docker-kill-primary")
        self.assertEqual(primary.replication_host, "postgres-us")
        self.assertEqual(partition.fault, "toxiproxy-partition")
        self.assertEqual(partition.replication_host, "postgres-us.proxy")
        self.assertEqual(partition.latency_ms, 0)
        self.assertEqual(slow.fault, "docker-kill-primary")
        self.assertEqual(slow.replication_host, "postgres-us")
        self.assertEqual(slow.latency_ms, 200)
        self.assertEqual(slow.jitter_ms, 0)
        self.assertEqual(slow.toxicity, 1.0)
        self.assertEqual(config.toxiproxy.image, "ghcr.io/shopify/toxiproxy:2.12.0")
        self.assertNotIn("pull-request", partition.profiles)
        self.assertNotIn("pull-request", slow.profiles)
        overlay = (ROOT / "docker-compose.failover-drill-extended.yml").read_text(encoding="utf-8")
        self.assertIn(f"image: {config.toxiproxy.image}", overlay)
        self.assertIn("postgres-us.proxy", overlay)
        self.assertIn("postgres-us.direct", overlay)
        self.assertNotIn("app-us:", overlay)

    def test_jitter_and_pull_request_extended_scenarios_are_rejected(self) -> None:
        payload = json.loads(CONFIG.read_text(encoding="utf-8"))
        payload["scenarios"][2]["jitter_ms"] = 5
        with self.assertRaises(ValueError):
            load_config(self._write(payload))
        payload = json.loads(CONFIG.read_text(encoding="utf-8"))
        payload["scenarios"][1]["profiles"] = ["pull-request"]
        with self.assertRaises(ValueError):
            load_config(self._write(payload))
        with self.assertRaises(ValueError):
            resolve_profile("aurora")

    def test_subscription_host_is_taken_from_the_scenario(self) -> None:
        sql = subscription_sql("postgres-us.proxy")
        self.assertIn("host=postgres-us.proxy", sql)
        self.assertNotIn("host=postgres-us ", sql)
        with self.assertRaises(ValueError):
            subscription_sql("postgres-us;drop")

    def _write(self, payload: dict) -> Path:
        directory = Path(tempfile.mkdtemp())
        path = directory / "failover-drill.json"
        path.write_text(json.dumps(payload), encoding="utf-8")
        self.addCleanup(lambda: path.unlink(missing_ok=True))
        return path


class ToxiproxyRequestTest(unittest.TestCase):
    def test_latency_toxic_is_fixed_for_every_connection(self) -> None:
        body = latency_toxic_body(200, 0, 1.0)
        self.assertEqual(body["type"], "latency")
        self.assertEqual(body["stream"], "downstream")
        self.assertEqual(body["toxicity"], 1.0)
        self.assertEqual(body["attributes"], {"latency": 200, "jitter": 0})
        proxy = proxy_body("cross-region-postgres-us", "0.0.0.0:5432", "postgres-us.direct:5432", False)
        self.assertFalse(proxy["enabled"])
        with self.assertRaises(ValueError):
            latency_toxic_body(200, 15, 1.0)
        with self.assertRaises(ValueError):
            latency_toxic_body(200, 0, 0.5)


class NightlyReportTest(unittest.TestCase):
    def test_combined_report_keeps_the_primary_aggregate_and_fails_closed(self) -> None:
        primary = _scenario("primary-kill", passed=True, rto=1.25)
        partition = _scenario("network-partition", passed=False, rto=4.5)
        slow = _scenario("slow-db", passed=True, rto=1.5)
        combined = combine_scenario_reports([primary, partition, slow])
        self.assertEqual(combined["aggregate"]["rto_seconds"], 1.25)
        self.assertEqual(combined["scenario"], "primary-kill")
        self.assertTrue(combined["gate"]["passed"])
        self.assertFalse(combined["nightly_gate"]["passed"])
        self.assertTrue(any(item.startswith("network-partition:") for item in combined["nightly_gate"]["failures"]))
        self.assertEqual(combined["measurement_scope"], NIGHTLY_MEASUREMENT_SCOPE)
        self.assertIsNone(partition["baseline"])

    def test_rendered_nightly_results_use_the_toxiproxy_label_and_the_same_fields(self) -> None:
        combined = combine_scenario_reports(
            [
                _scenario("primary-kill", passed=True, rto=0.876),
                _scenario("network-partition", passed=True, rto=1.2),
                _scenario("slow-db", passed=False, rto=None),
            ]
        )
        rendered = render_nightly_markdown(combined)
        body = render_issue_body(combined)
        self.assertIn(NIGHTLY_MEASUREMENT_SCOPE, rendered)
        self.assertIn(NIGHTLY_MEASUREMENT_SCOPE, body)
        self.assertIn(MARKER, body)
        for name in ("primary-kill", "network-partition", "slow-db"):
            self.assertIn(f"### {name}", rendered)
        for field in (
            "rto_seconds",
            "rpo_seconds",
            "rpo_lost_writes",
            "duplicate_writes",
            "error_count",
            "p99_latency_ms",
            "fault_injected_at",
            "writer_switched_at",
            "commit_sha",
            "run_count",
            "config_sha256",
        ):
            self.assertIn(field, body)
        for label in (
            "RTO (s)",
            "RPO (s)",
            "RPO lost writes",
            "Duplicate writes",
            "Errors during switchover",
            "p99 latency during switchover (ms)",
            "`fault_injected_at`",
            "`writer_switched_at`",
            "`commit_sha`",
            "`run_count`",
            "`config_sha256`",
        ):
            self.assertIn(label, rendered)
        self.assertIn("local mock (containers + fault injection), not real Aurora Global", rendered)
        comment = render_comment(_scenario("primary-kill", passed=True, rto=0.876))
        self.assertNotIn("Toxiproxy", comment)

    def test_one_tracking_issue_is_created_or_updated(self) -> None:
        failed = combine_scenario_reports([_scenario("primary-kill", passed=False, rto=None)])
        created = plan_tracking_issue(failed, [])
        self.assertEqual(created.action, "create")
        self.assertEqual(created.title, TITLE)
        self.assertIn(MARKER, created.body)
        self.assertIn("drill-report.json", created.body)
        older = {"number": 4, "title": TITLE, "body": f"{MARKER}\nold"}
        newer = {"number": 9, "title": TITLE, "body": "duplicate"}
        updated = plan_tracking_issue(failed, [newer, older])
        self.assertEqual(updated.action, "update")
        self.assertEqual(updated.number, 4)
        self.assertEqual(updated.close_numbers, (9,))
        passed = combine_scenario_reports([_scenario("primary-kill", passed=True, rto=1)])
        self.assertEqual(plan_tracking_issue(passed, []).action, "none")
        closed = plan_tracking_issue(passed, [older])
        self.assertEqual(closed.action, "close")
        self.assertEqual(closed.number, 4)
        self.assertIn(NIGHTLY_MEASUREMENT_SCOPE, closed.body)


class BaselinePublicationTest(unittest.TestCase):
    def test_baseline_marker_is_written_only_for_a_passing_primary_on_main(self) -> None:
        self.assertTrue(should_publish_baseline("nightly", "refs/heads/main", True))
        self.assertFalse(should_publish_baseline("nightly", "refs/heads/cursor/nightly-failover-drill-44ce", True))
        self.assertFalse(should_publish_baseline("nightly", "refs/heads/main", False))
        self.assertFalse(should_publish_baseline("pull-request", "refs/heads/main", True))
        self.assertFalse(should_publish_baseline("nightly", "", True))


class WorkflowContractTest(unittest.TestCase):
    def test_nightly_workflow_is_scheduled_on_free_runners_and_does_not_gate_pull_requests(self) -> None:
        nightly = (ROOT / ".github/workflows/failover-drill-nightly.yml").read_text(encoding="utf-8")
        e2e = (ROOT / ".github/workflows/e2e.yml").read_text(encoding="utf-8")
        self.assertRegex(nightly, r"(?m)^on:\n  schedule:\n    - cron: \"0 6 \* \* \*\"\n  workflow_dispatch:\n\npermissions: \{\}\n")
        self.assertNotIn("register:", nightly)
        self.assertNotIn("\n  push:", nightly)
        self.assertIn("contents: read", nightly)
        self.assertIn("issues: write", nightly)
        self.assertIn("github.ref == 'refs/heads/main'", nightly)
        self.assertIn('cron: "0 6 * * *"', nightly)
        self.assertNotIn("cursor/nightly-failover-drill-44ce", nightly)
        self.assertFalse((ROOT / ".github/workflows/dispatch-nightly-once.yml").exists())
        self.assertEqual(set(re.findall(r"runs-on:\s*(\S+)", nightly)), {"ubuntu-latest"})
        for banned in ("self-hosted", "macos-", "windows-", "ubuntu-latest-", "gpu"):
            self.assertNotIn(banned, nightly)
        self.assertIn("FAILOVER_DRILL_PROFILE: nightly", nightly)
        self.assertIn("failover-drill-baseline", nightly)
        self.assertIn("issues: write", nightly)
        self.assertNotIn("schedule:", e2e)
        self.assertNotIn("FAILOVER_DRILL_PROFILE", e2e)
        self.assertNotIn("failover-drill-baseline", e2e)
        fetch = (ROOT / "scripts/failover-drill-fetch-baseline.sh").read_text(encoding="utf-8")
        loop = next(line for line in fetch.splitlines() if line.strip().startswith("for workflow"))
        self.assertLess(loop.index("failover-drill-nightly.yml"), loop.index("e2e.yml"))
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        adr = (ROOT / "docs/adr/0002-nightly-failover-drill.md").read_text(encoding="utf-8")
        self.assertIn(NIGHTLY_MEASUREMENT_SCOPE, readme)
        self.assertIn(NIGHTLY_MEASUREMENT_SCOPE, adr)
        self.assertNotIn("Monday schedule stays", readme)


def _scenario(name: str, passed: bool, rto: float | None) -> dict:
    scope = (
        "local mock (containers + fault injection), not real Aurora Global"
        if name == "primary-kill"
        else NIGHTLY_MEASUREMENT_SCOPE
    )
    return {
        "scenario": name,
        "profile": "nightly",
        "measurement_scope": scope,
        "commit_sha": "abc123",
        "workflow_run_url": "https://example.test/run/9",
        "run_count": 3,
        "config_sha256": "a" * 64,
        "config": {"iterations": 3, "gates": {}},
        "runtime": {
            "failure_injection": "toxiproxy-partition" if name == "network-partition" else "docker-kill-primary",
            "toxiproxy_latency_ms": 200 if name == "slow-db" else 0,
            "toxiproxy_jitter_ms": 0,
            "toxiproxy_toxicity": 1,
        },
        "runs": [
            {
                "index": 1,
                "fault_injected_at": "2026-10-22T06:00:00.000Z",
                "writer_switched_at": "2026-10-22T06:00:01.000Z",
                "rto_seconds": rto,
            }
        ],
        "aggregate": {
            "method": "median",
            "rto_seconds": rto,
            "rpo_seconds": 0.0,
            "rpo_lost_writes": 0,
            "duplicate_writes": 0,
            "error_count": 0,
            "p99_latency_ms": 10.0,
        },
        "baseline": {"aggregate": {"rto_seconds": 1.0}} if name == "primary-kill" else None,
        "deltas": {},
        "gate": {
            "passed": passed,
            "failures": [] if passed else [f"{name} failed"],
        },
    }


if __name__ == "__main__":
    unittest.main()

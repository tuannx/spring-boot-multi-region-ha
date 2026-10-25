from __future__ import annotations

import unittest
from pathlib import Path

from failover_drill.config import DrillConfig, DrillGates, load_config
from failover_drill.metrics import (
    Attempt,
    duplicate_writes,
    evaluate_gate,
    median_metric,
    recovery_point,
    recovery_time_seconds,
    switchover_error_count,
    switchover_p99_ms,
)
from failover_drill.architecture_diff import composite_shell
from failover_drill.report import (
    ARCHITECTURE_DIFF_UNAVAILABLE,
    MEASUREMENT_SCOPE,
    aggregate_runs,
    build_report,
    record_unavailable_architecture_diff,
    render_comment,
    render_readme_section,
)


def _gates() -> DrillGates:
    return DrillGates(
        rto_seconds_max=60,
        rpo_seconds_max=2,
        rpo_lost_writes_max=0,
        duplicate_writes_max=0,
        regression_ratio_max=0.2,
        rto_regression_min_delta_seconds=1.0,
        rpo_regression_min_delta_seconds=0.5,
        error_count_regression_min_delta=2,
        regression_metrics=("rto_seconds", "rpo_seconds", "error_count"),
    )


def _write(name: str, started: int, finished: int, status: int | None = 201) -> Attempt:
    return Attempt("write", name, started, finished, status)


class RecoveryMetricsTest(unittest.TestCase):
    def test_rto_starts_at_the_kill_and_ignores_writes_that_started_before_promotion(self) -> None:
        attempts = [
            _write("before", 900, 1100),
            _write("during-kill", 1050, 1300),
            _write("after", 1600, 2400),
        ]
        present = {"before", "during-kill", "after"}
        self.assertEqual(recovery_time_seconds(attempts, 1000, 1500, present), 1.4)

    def test_rpo_is_zero_when_every_acknowledgement_is_on_the_new_writer(self) -> None:
        attempts = [_write("kept", 100, 200)]
        self.assertEqual(recovery_point(attempts, 1000, {"kept"}), (0.0, 0))

    def test_rpo_spans_the_oldest_missing_acknowledgement_to_the_kill(self) -> None:
        attempts = [
            _write("kept", 100, 200),
            _write("lost-old", 400, 500),
            _write("lost-new", 800, 900),
        ]
        self.assertEqual(recovery_point(attempts, 1000, {"kept"}), (0.5, 2))

    def test_a_loss_acknowledged_after_the_kill_counts_and_adds_no_seconds(self) -> None:
        attempts = [_write("inflight", 1000, 1200)]
        self.assertEqual(recovery_point(attempts, 1100, set()), (0.0, 1))

    def test_duplicates_count_extra_rows(self) -> None:
        self.assertEqual(duplicate_writes({"a": 1, "b": 3}), 2)

    def test_errors_and_p99_use_the_switchover_window(self) -> None:
        attempts = [
            _write("warmup", 100, 150),
            _write("fail", 900, 1400, status=500),
            _write("slow", 1200, 3000, status=None),
            _write("recovered", 1600, 2000),
            _write("after", 2500, 2600, status=500),
        ]
        present = {"recovered"}
        self.assertEqual(switchover_error_count(attempts, 1000, 1500, present, 50000), 2)
        self.assertEqual(switchover_p99_ms(attempts, 1000, 1500, present, 50000), 1800.0)

    def test_median_uses_the_middle_run(self) -> None:
        self.assertEqual(median_metric([9.0, 1.0, 4.0], 3), 4.0)
        self.assertIsNone(median_metric([1.0, None, 3.0], 3))

    def test_absolute_limits_use_greater_than(self) -> None:
        passing = _run(rto=60, rpo=2, errors=0)
        failing = _run(rto=60.001, rpo=2.001, errors=0)
        self.assertTrue(evaluate_gate(_aggregate(passing), None, _gates(), [passing])["passed"])
        result = evaluate_gate(_aggregate(failing), None, _gates(), [failing])
        self.assertFalse(result["passed"])
        self.assertTrue(any("RTO" in failure for failure in result["failures"]))
        self.assertTrue(any("RPO" in failure for failure in result["failures"]))

    def test_regression_fails_above_twenty_percent_and_passes_at_the_boundary(self) -> None:
        current = _aggregate(_run(rto=12, rpo=0, errors=6))
        at_limit = evaluate_gate(current, {"rto_seconds": 10, "rpo_seconds": 0, "error_count": 5}, _gates(), [_run()])
        over = evaluate_gate(current, {"rto_seconds": 10, "rpo_seconds": 0, "error_count": 4}, _gates(), [_run()])
        self.assertTrue(at_limit["passed"])
        self.assertFalse(over["passed"])
        self.assertTrue(any("error_count" in failure for failure in over["failures"]))

    def test_a_missing_baseline_skips_regression(self) -> None:
        current = _aggregate(_run(rto=1, rpo=0.1, errors=1))
        self.assertTrue(evaluate_gate(current, None, _gates(), [_run(rto=1, rpo=0.1, errors=1)])["passed"])

    def test_lost_writes_fail_even_when_rpo_seconds_is_zero(self) -> None:
        run = _run(rto=1, rpo=0, errors=0, lost=1)
        result = evaluate_gate(_aggregate(run), None, _gates(), [run, run, run])
        self.assertFalse(result["passed"])
        self.assertTrue(any("rpo_lost_writes 1 exceeds 0" in failure for failure in result["failures"]))

    def test_one_run_with_a_lost_write_fails_when_the_median_is_zero(self) -> None:
        runs = [_run(index=1, lost=0), _run(index=2, lost=1), _run(index=3, lost=0)]
        aggregate = aggregate_runs(runs)
        self.assertEqual(aggregate["rpo_lost_writes"], 0)
        result = evaluate_gate(aggregate, None, _gates(), runs)
        self.assertFalse(result["passed"])
        self.assertTrue(any("run 2 rpo_lost_writes 1 exceeds 0" in failure for failure in result["failures"]))

    def test_one_run_with_a_duplicate_fails_when_the_median_is_zero(self) -> None:
        runs = [_run(index=1), _run(index=2, duplicates=1), _run(index=3)]
        aggregate = aggregate_runs(runs)
        self.assertEqual(aggregate["duplicate_writes"], 0)
        result = evaluate_gate(aggregate, None, _gates(), runs)
        self.assertFalse(result["passed"])
        self.assertTrue(any("run 2 duplicate_writes 1 exceeds 0" in failure for failure in result["failures"]))

    def test_rto_regression_needs_the_ratio_and_a_one_second_floor(self) -> None:
        self.assertTrue(self._compared(rto=1.3, baseline_rto=1.0)["passed"])
        over = self._compared(rto=2.0, baseline_rto=1.0)
        self.assertFalse(over["passed"])
        self.assertTrue(any("rto_seconds regressed" in failure for failure in over["failures"]))
        self.assertTrue(self._compared(rto=0.9, baseline_rto=0.0)["passed"])
        from_zero = self._compared(rto=1.0, baseline_rto=0.0)
        self.assertFalse(from_zero["passed"])
        self.assertTrue(any("absolute floor 1.0s" in failure for failure in from_zero["failures"]))

    def test_rpo_regression_needs_the_ratio_and_a_half_second_floor(self) -> None:
        self.assertTrue(self._compared(rpo=0.4, baseline_rpo=0.0)["passed"])
        from_zero = self._compared(rpo=0.5, baseline_rpo=0.0)
        self.assertFalse(from_zero["passed"])
        self.assertTrue(any("rpo_seconds regressed from 0 to 0.5s" in failure for failure in from_zero["failures"]))
        self.assertTrue(self._compared(rpo=1.3, baseline_rpo=1.0)["passed"])
        over = self._compared(rpo=1.5, baseline_rpo=1.0)
        self.assertFalse(over["passed"])
        self.assertTrue(any("rpo_seconds regressed" in failure for failure in over["failures"]))

    def test_error_count_regression_needs_the_ratio_and_two_extra_errors(self) -> None:
        self.assertTrue(self._compared(errors=1, baseline_errors=0)["passed"])
        from_zero = self._compared(errors=2, baseline_errors=0)
        self.assertFalse(from_zero["passed"])
        self.assertTrue(any("error_count regressed from 0 to 2" in failure for failure in from_zero["failures"]))
        self.assertTrue(self._compared(errors=5, baseline_errors=4)["passed"])
        over = self._compared(errors=6, baseline_errors=4)
        self.assertFalse(over["passed"])
        self.assertTrue(any("error_count regressed" in failure for failure in over["failures"]))

    def _compared(
        self,
        rto: float = 1,
        rpo: float = 0,
        errors: int = 0,
        baseline_rto: float = 1,
        baseline_rpo: float = 0,
        baseline_errors: int = 0,
    ) -> dict:
        run = _run(rto=rto, rpo=rpo, errors=errors)
        current = _aggregate(run)
        baseline = {"rto_seconds": baseline_rto, "rpo_seconds": baseline_rpo, "error_count": baseline_errors}
        return evaluate_gate(current, baseline, _gates(), [run])

    def test_p99_increase_is_not_a_gate(self) -> None:
        current = _aggregate(_run(p99=9000))
        baseline = {"rto_seconds": 8, "rpo_seconds": 0, "error_count": 1, "p99_latency_ms": 10}
        self.assertTrue(evaluate_gate(current, baseline, _gates(), [_run()])["passed"])

    def test_comment_table_is_rendered_from_the_report(self) -> None:
        report = {
            "commit_sha": "abc123",
            "workflow_run_url": "https://example.test/run/1",
            "config": {
                "iterations": 3,
                "gates": {
                    "rto_seconds_max": 60,
                    "rpo_seconds_max": 2,
                    "rpo_lost_writes_max": 0,
                    "duplicate_writes_max": 0,
                    "regression_ratio_max": 0.2,
                    "rto_regression_min_delta_seconds": 1.0,
                    "rpo_regression_min_delta_seconds": 0.5,
                    "error_count_regression_min_delta": 2,
                    "regression_metrics": ["rto_seconds", "rpo_seconds", "error_count"],
                },
            },
            "aggregate": {
                "rto_seconds": 8.102,
                "rpo_seconds": 0.04,
                "rpo_lost_writes": 1,
                "duplicate_writes": 2,
                "error_count": 3,
                "p99_latency_ms": 8102.5,
            },
            "baseline": {
                "aggregate": {
                    "rto_seconds": 7,
                    "rpo_seconds": 0.02,
                    "rpo_lost_writes": 0,
                    "duplicate_writes": 2,
                    "error_count": 3,
                    "p99_latency_ms": 100,
                }
            },
            "deltas": {
                "rto_seconds": {
                    "absolute": 1.102,
                    "ratio": 0.1574,
                    "unbounded": False,
                    "direction": "up",
                    "regressed": False,
                }
            },
            "gate": {"passed": True, "failures": []},
        }
        comment = render_comment(report)
        self.assertIn("<!-- failover-drill-report -->", comment)
        self.assertIn(MEASUREMENT_SCOPE, comment)
        self.assertIn("| RPO lost writes | 1 | 0 |", comment)
        self.assertIn("8.102", comment)
        self.assertIn("8102.5", comment)
        self.assertIn("+1.102 (+15.7%) up", comment)
        self.assertIn("https://example.test/run/1", comment)
        self.assertIn("otlp-trace.json", comment)
        self.assertIn("fault_injected_at", comment)
        self.assertIn("<!-- architecture-engineer-diff -->", comment)
        self.assertIn("## Architecture diff", comment)
        self.assertNotIn(ARCHITECTURE_DIFF_UNAVAILABLE, comment)
        self.assertNotIn("hand-edited", comment)

    def test_an_empty_architecture_diff_slot_is_marked_unavailable(self) -> None:
        comment = "## Architecture diff\n\n<!-- architecture-engineer-diff -->\n"
        updated, unavailable = record_unavailable_architecture_diff(comment)
        self.assertTrue(unavailable)
        self.assertEqual(
            updated,
            "## Architecture diff\n\n<!-- architecture-engineer-diff -->\nAE diff unavailable\n",
        )
        again, still_unavailable = record_unavailable_architecture_diff(updated)
        self.assertFalse(still_unavailable)
        self.assertEqual(again, updated)

    def test_a_filled_architecture_diff_slot_is_left_unchanged(self) -> None:
        comment = (
            "## Architecture diff\n\n<!-- architecture-engineer-diff -->\n"
            "writer pool moved during the kill\n"
        )
        updated, unavailable = record_unavailable_architecture_diff(comment)
        self.assertFalse(unavailable)
        self.assertEqual(updated, comment)

    def test_composite_action_commands_receive_the_drill_paths(self) -> None:
        action = """
runs:
  using: composite
  steps:
    - shell: bash
      run: tool --trace ${{ inputs.trace }} --report ${{ inputs.report }}
    - run: |
        splice --comment ${{ inputs.comment }}
        echo ${{ github.action_path }}
"""
        shell = composite_shell(
            action,
            trace="reports/failover-drill/otlp-trace.json",
            report="reports/failover-drill/drill-report.json",
            comment="reports/failover-drill/comment.md",
            action_path="/tmp/architecture-engineer/action",
        )
        self.assertIn("tool --trace reports/failover-drill/otlp-trace.json", shell or "")
        self.assertIn("--report reports/failover-drill/drill-report.json", shell or "")
        self.assertIn("splice --comment reports/failover-drill/comment.md", shell or "")
        self.assertIn("/tmp/architecture-engineer/action", shell or "")

    def test_a_non_composite_action_is_not_run_as_shell(self) -> None:
        self.assertIsNone(composite_shell("runs:\n  using: node20\n", trace="", report="", comment="", action_path=""))

    def test_whitespace_after_the_architecture_diff_marker_counts_as_empty(self) -> None:
        comment = "<!-- architecture-engineer-diff -->\n\n  \n"
        updated, unavailable = record_unavailable_architecture_diff(comment)
        self.assertTrue(unavailable)
        self.assertEqual(updated, "<!-- architecture-engineer-diff -->\nAE diff unavailable\n")

    def test_missing_baseline_renders_an_empty_main_column(self) -> None:
        runs = [ _run(index=1), _run(index=2, rto=9), _run(index=3, rto=11)]
        comment = render_comment(
            {
                "commit_sha": "abc",
                "workflow_run_url": None,
                "config": {
                    "iterations": 3,
                    "gates": {
                        "rto_seconds_max": 60,
                        "rpo_seconds_max": 2,
                        "rpo_lost_writes_max": 0,
                        "duplicate_writes_max": 0,
                        "regression_ratio_max": 0.2,
                        "rto_regression_min_delta_seconds": 1.0,
                        "rpo_regression_min_delta_seconds": 0.5,
                        "error_count_regression_min_delta": 2,
                        "regression_metrics": ["rto_seconds"],
                    },
                },
                "aggregate": aggregate_runs(runs),
                "baseline": None,
                "deltas": {},
                "gate": {"passed": True, "failures": []},
            }
        )
        self.assertIn("| RTO (s) | 9.000 | — | n/a |", comment)
        self.assertIn("No main baseline", comment)

    def test_report_records_run_count_and_config_hash(self) -> None:
        config = load_config(Path(__file__).resolve().parents[2] / "config" / "failover-drill.json")
        report = build_report(config, [_run(), _run(index=2), _run(index=3)], "abc", None, {}, None)
        self.assertEqual(report["commit_sha"], "abc")
        self.assertEqual(report["run_count"], 3)
        self.assertEqual(len(report["config_sha256"]), 64)
        self.assertIn("rpo_seconds", report["runs"][0])
        self.assertIn("rpo_lost_writes", report["runs"][0])

    def test_repository_config_matches_the_required_limits(self) -> None:
        config = load_config(Path(__file__).resolve().parents[2] / "config" / "failover-drill.json")
        self.assertIsInstance(config, DrillConfig)
        self.assertEqual(config.iterations, 3)
        self.assertEqual(config.gates.rto_seconds_max, 60)
        self.assertEqual(config.gates.rpo_seconds_max, 2)
        self.assertEqual(config.gates.rpo_lost_writes_max, 0)
        self.assertEqual(config.gates.duplicate_writes_max, 0)
        self.assertEqual(config.gates.regression_ratio_max, 0.2)
        self.assertEqual(config.gates.rto_regression_min_delta_seconds, 1.0)
        self.assertEqual(config.gates.rpo_regression_min_delta_seconds, 0.5)
        self.assertEqual(config.gates.error_count_regression_min_delta, 2)
        self.assertNotIn("p99_latency_ms", config.gates.regression_metrics)

    def test_readme_results_table_is_generated_from_the_config(self) -> None:
        config = load_config(Path(__file__).resolve().parents[2] / "config" / "failover-drill.json")
        readme = (Path(__file__).resolve().parents[2] / "README.md").read_text(encoding="utf-8")
        section = render_readme_section(config)
        begin = "<!-- failover-drill-readme:begin -->"
        end = "<!-- failover-drill-readme:end -->"
        self.assertIn(begin, readme)
        self.assertIn(end, readme)
        extracted = readme.split(begin, 1)[1].split(end, 1)[0]
        self.assertEqual(extracted, "\n" + section)
        self.assertIn(MEASUREMENT_SCOPE, section)
        self.assertIn("./scripts/failover-drill.sh", readme)
        self.assertIn("not maintained by hand", section)
        self.assertNotIn("lost_acknowledged_writes", readme)


def _run(
    index: int = 1,
    rto: float = 8,
    rpo: float = 0,
    errors: int = 1,
    p99: float = 100,
    lost: int = 0,
    duplicates: int = 0,
) -> dict:
    return {
        "index": index,
        "rto_seconds": rto,
        "rpo_seconds": rpo,
        "rpo_lost_writes": lost,
        "duplicate_writes": duplicates,
        "error_count": errors,
        "p99_latency_ms": p99,
        "driver_failover_verified": True,
    }


def _aggregate(run: dict) -> dict:
    return aggregate_runs([run, run, run])


if __name__ == "__main__":
    unittest.main()

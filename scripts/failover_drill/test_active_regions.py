from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from failover_drill import main as drill_main
from failover_drill.active_regions import (
    PAIR,
    REGIONS,
    SIDES,
    application_name,
    convergence_lag_seconds,
    cross_application_names,
    failover_verified,
    names_by_region_code,
    overlapping_p99_ms,
    overlapping_write_errors,
    publication_sql,
    sequence_sql,
    recovery_groups,
    region_by_id,
    regional_switchover_end_ms,
    replication_endpoint,
    subscription_sql,
    summed_duplicate_writes,
    sync_target,
    synchronous_standby_sql,
)
from failover_drill.config import ACTIVE_REGION_IDS, load_config
from failover_drill.metrics import Attempt, evaluate_gate, write_conflicts
from failover_drill.nightly_report import ACTIVE_REGION_MEASUREMENT_SCOPE, measurement_scope_for
from failover_drill.test_metrics import _gates

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / "config" / "failover-drill.json"


def _write(name: str, started: int, finished: int, status: int | None = 201) -> Attempt:
    return Attempt("write", name, started, finished, status)


class ActiveRegionTopologyTest(unittest.TestCase):
    def test_regions_and_sync_targets_match_the_nightly_scenarios(self) -> None:
        self.assertEqual(tuple(region.region_id for region in REGIONS), ACTIVE_REGION_IDS)
        config = load_config(CONFIG)
        by_name = {scenario.name: scenario for scenario in config.scenarios}
        for name in ("lose-1-of-4", "lose-2-of-4", "partition-2-2"):
            scenario = by_name[name]
            self.assertEqual(scenario.topology, "active-regions")
            self.assertEqual(scenario.profiles, ("nightly",))
            self.assertEqual(scenario.iterations, 1)
            self.assertEqual(scenario.write_conflicts_max, 0)
            self.assertGreater(scenario.convergence_lag_seconds_max, 0)
            self.assertFalse(scenario.uses_toxiproxy)
            for region in REGIONS:
                target = sync_target(name, region.region_id)
                self.assertNotIn(target, scenario.killed_regions)
                self.assertIn(target, ACTIVE_REGION_IDS)
            for killed in scenario.killed_regions:
                self.assertEqual(sync_target(name, killed), PAIR[killed])
                self.assertNotIn(PAIR[killed], scenario.killed_regions)
        self.assertEqual(sync_target("lose-1-of-4", "ap-northeast-1"), "us-east-1")
        self.assertNotEqual(sync_target("lose-1-of-4", "ap-northeast-1"), PAIR["ap-northeast-1"])
        self.assertEqual(sync_target("lose-2-of-4", "us-east-1"), "ap-southeast-1")
        self.assertEqual(by_name["lose-1-of-4"].killed_regions, ("ap-southeast-1",))
        self.assertEqual(by_name["lose-2-of-4"].killed_regions, ("eu-west-1", "ap-northeast-1"))
        self.assertEqual(by_name["partition-2-2"].killed_regions, ())

    def test_cross_pair_replication_uses_toxiproxy_and_pairs_stay_direct(self) -> None:
        us = region_by_id("us-east-1")
        eu = region_by_id("eu-west-1")
        apse1 = region_by_id("ap-southeast-1")
        self.assertEqual(replication_endpoint(us, eu, "split-pairs"), ("postgres-us", 5432))
        self.assertEqual(replication_endpoint(us, apse1, "split-pairs"), ("toxiproxy", 5601))
        self.assertEqual(replication_endpoint(apse1, us, "split-pairs"), ("toxiproxy", 5603))
        self.assertEqual(replication_endpoint(apse1, region_by_id("ap-northeast-1"), "split-pairs"), ("postgres-apse1", 5432))
        self.assertEqual(replication_endpoint(us, apse1, "kill-regions"), ("postgres-us", 5432))
        self.assertEqual(SIDES["us-east-1"], SIDES["eu-west-1"])
        self.assertNotEqual(SIDES["us-east-1"], SIDES["ap-southeast-1"])
        sql = subscription_sql(us, apse1, "split-pairs")
        self.assertIn("host=toxiproxy port=5601", sql)
        self.assertIn("application_name=shard_use1_to_aps1", sql)
        self.assertIn("origin = none", sql)
        self.assertIn(f"PUBLICATION {publication_sql(us).split()[2]}", sql)
        direct = subscription_sql(us, eu, "split-pairs")
        self.assertIn("host=postgres-us port=5432", direct)
        self.assertNotIn("toxiproxy", direct)
        published = publication_sql(apse1)
        self.assertIn("WHERE (region = 'ap-southeast-1')", published)
        widened = sequence_sql(apse1)
        self.assertIn("ALTER SEQUENCE products_id_seq AS bigint", widened)
        self.assertIn("3000000000", widened)
        self.assertIn("false", widened)
        self.assertIn("CREATE PUBLICATION shard_aps1", published)
        standby = synchronous_standby_sql("lose-2-of-4", us)
        self.assertIn("ANY 1 (shard_use1_to_aps1)", standby)
        self.assertIn("remote_apply", standby)
        killed_standby = synchronous_standby_sql("lose-1-of-4", apse1)
        self.assertIn("ANY 1 (shard_aps1_to_apn1)", killed_standby)
        same_side = {application_name(us, eu), application_name(eu, us)}
        self.assertTrue(same_side.isdisjoint(cross_application_names()))
        self.assertIn(application_name(us, apse1), cross_application_names())

    def test_compose_files_describe_four_home_writers(self) -> None:
        active = (ROOT / "docker-compose.active-regions.yml").read_text(encoding="utf-8")
        partition = (ROOT / "docker-compose.active-regions-partition.yml").read_text(encoding="utf-8")
        floci = (ROOT / "docker-compose.floci-active-regions.yml").read_text(encoding="utf-8")
        for service in ("postgres-us", "postgres-eu", "postgres-apse1", "postgres-apne1"):
            self.assertIn(f"{service}:", active)
        for region in ("us-east-1", "eu-west-1", "ap-southeast-1", "ap-northeast-1"):
            self.assertIn(region, active)
        self.assertIn("SHARD_OWNERSHIP_ENFORCED", active)
        self.assertIn("READ_FAILOVER_MODE: strict-writer", active)
        self.assertIn("name: active-regions", active)
        self.assertNotIn("restart:", active)
        self.assertIn("ghcr.io/shopify/toxiproxy:2.12.0", partition)
        self.assertIn("8474:8474", partition)
        self.assertIn("FLOCI_DEFAULT_REGION: ap-southeast-1", floci)
        self.assertIn("FLOCI_DEFAULT_REGION: ap-northeast-1", floci)
        self.assertNotIn("multiregion-floci-app", floci)


class ActiveRegionMetricTest(unittest.TestCase):
    def test_conflicts_count_divergent_rows_and_shard_rejections(self) -> None:
        rows = {
            "us-east-1": [("same", "us-east-1", "1.00"), ("split", "us-east-1", "1.00")],
            "eu-west-1": [("same", "us-east-1", "1.00"), ("split", "eu-west-1", "2.00")],
        }
        self.assertEqual(write_conflicts(rows, 0), 1)
        self.assertEqual(write_conflicts(rows, 2), 3)

    def test_duplicates_are_counted_inside_each_database(self) -> None:
        counts = {
            "us-east-1": {"a": 2, "b": 1},
            "eu-west-1": {"a": 1, "b": 1},
        }
        self.assertEqual(summed_duplicate_writes(counts), 1)

    def test_recovery_uses_the_slowest_region_group(self) -> None:
        attempts = [
            _write("drill-r1-use1-p-n000000", 2000, 2500),
            _write("drill-r1-euw1-p-n000000", 2000, 4000),
            _write("drill-r1-aps1-p-n000000", 2000, 3000),
            _write("drill-r1-apn1-p-n000000", 2000, 2800),
        ]
        present = {attempt.name for attempt in attempts}
        grouped = names_by_region_code(present)
        self.assertEqual(len(grouped["use1"]), 1)
        groups = recovery_groups(present)
        end_ms = regional_switchover_end_ms(attempts, 1000, 1500, groups, 50000)
        self.assertEqual(end_ms, 4000)
        self.assertEqual(overlapping_write_errors(attempts, 1000, end_ms), 0)
        late_failure = _write("drill-r1-use1-p-n000001", 2600, 3900, status=500)
        self.assertEqual(overlapping_write_errors([*attempts, late_failure], 1000, end_ms), 1)
        self.assertEqual(overlapping_p99_ms(attempts, 1000, end_ms), 2000.0)

    def test_convergence_above_the_limit_fails_the_gate(self) -> None:
        self.assertEqual(convergence_lag_seconds(None, 15), 16.0)
        self.assertEqual(convergence_lag_seconds(15, 15), 15.0)
        run = {
            "index": 1,
            "rto_seconds": 1.0,
            "rpo_seconds": 0.0,
            "rpo_lost_writes": 0,
            "duplicate_writes": 0,
            "error_count": 0,
            "p99_latency_ms": 10.0,
            "write_conflicts": 0,
            "convergence_lag_seconds": convergence_lag_seconds(None, 15),
            "driver_failover_verified": True,
            "verification": "active-region-routing",
        }
        aggregate = dict(run)
        result = evaluate_gate(aggregate, None, _gates(), [run])
        self.assertFalse(result["passed"])
        self.assertTrue(any("convergence_lag_seconds 16.0 exceeds 15" in failure for failure in result["failures"]))

    def test_unverified_routing_names_the_live_home_writer(self) -> None:
        run = {
            "index": 1,
            "rto_seconds": 1.0,
            "rpo_seconds": 0.0,
            "rpo_lost_writes": 0,
            "duplicate_writes": 0,
            "error_count": 0,
            "p99_latency_ms": 10.0,
            "write_conflicts": 0,
            "convergence_lag_seconds": 0.0,
            "driver_failover_verified": False,
            "verification": "active-region-routing",
        }
        result = evaluate_gate(run, None, _gates(), [run])
        self.assertTrue(any("live home writer" in failure for failure in result["failures"]))
        self.assertFalse(any("postgres-eu" in failure for failure in result["failures"]))

    def test_failover_verification_requires_the_killed_containers_to_stay_down(self) -> None:
        config = load_config(CONFIG)
        lose_one = next(scenario for scenario in config.scenarios if scenario.name == "lose-1-of-4")
        statuses = {}
        for region in REGIONS:
            statuses[region.container] = "running"
            statuses[region.app_container] = "running"
        killed = region_by_id("ap-southeast-1")
        statuses[killed.container] = "exited"
        statuses[killed.app_container] = "exited"
        self.assertTrue(failover_verified(lose_one, statuses, 1.2, False))
        statuses[killed.container] = "running"
        self.assertFalse(failover_verified(lose_one, statuses, 1.2, False))
        self.assertFalse(failover_verified(lose_one, statuses, None, False))
        split = next(scenario for scenario in config.scenarios if scenario.name == "partition-2-2")
        up = {region.container: "running" for region in REGIONS}
        up.update({region.app_container: "running" for region in REGIONS})
        self.assertTrue(failover_verified(split, up, 0.4, True))
        self.assertFalse(failover_verified(split, up, 0.4, False))

    def test_measurement_scope_is_the_four_writer_mock(self) -> None:
        self.assertEqual(measurement_scope_for("lose-1-of-4"), ACTIVE_REGION_MEASUREMENT_SCOPE)
        self.assertEqual(measurement_scope_for("partition-2-2"), ACTIVE_REGION_MEASUREMENT_SCOPE)
        self.assertNotIn("Toxiproxy", measurement_scope_for("lose-2-of-4"))


class ActiveScenarioDispatchTest(unittest.TestCase):
    def test_active_topology_skips_the_single_writer_stack(self) -> None:
        config = load_config(CONFIG)
        scenario = next(item for item in config.scenarios if item.name == "lose-1-of-4")
        with patch("failover_drill.main.run_active_scenario", return_value={"dispatched": True}) as dispatched:
            result = drill_main._run_scenario(
                Path(tempfile.mkdtemp()),
                config,
                scenario,
                "nightly",
                "abc",
                None,
                None,
                False,
            )
        self.assertEqual(result, {"dispatched": True})
        dispatched.assert_called_once()
        primary = next(item for item in config.scenarios if item.name == "primary-kill")
        self.assertEqual(primary.topology, "single-writer")


if __name__ == "__main__":
    unittest.main()

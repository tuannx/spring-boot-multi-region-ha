# ADR 0002: Nightly failover drill

## Decision

`.github/workflows/failover-drill-nightly.yml` runs the failover drill on `main` every day at 06:00 UTC and on `workflow_dispatch`. The job uses the free GitHub-hosted `ubuntu-latest` runner. It replaces the Monday 06:00 UTC schedule that ADR 0001 kept in the application E2E workflow. That schedule is removed, and the E2E job no longer uploads `failover-drill-baseline`.

The workflow grants no permissions of its own. The drill job sets `contents: read` and `issues: write`. There is no push trigger and no registration job.

The nightly command is `./scripts/failover-drill.sh` with `FAILOVER_DRILL_PROFILE=nightly`. The pull-request profile stays the default, so a pull request still runs only `primary-kill`. The nightly profile runs `primary-kill`, then `network-partition`, then `slow-db`. `config/failover-drill.json` is the only list of scenarios, thresholds, and the Toxiproxy image. Extended scenarios set `jitter_ms` to 0 and `toxicity` to 1.

A nightly `drill-report.json` keeps the `primary-kill` aggregate at the top level and stores every scenario under `scenarios`. Each scenario carries `rto_seconds`, `rpo_seconds`, `rpo_lost_writes`, `duplicate_writes`, `error_count`, `p99_latency_ms`, per-run `fault_injected_at` and `writer_switched_at`, plus `commit_sha`, `run_count`, and `config_sha256`. The pull-request regression gate reads that top-level aggregate, so it compares a container kill with a container kill. When `primary-kill` passes and `github.ref` is `refs/heads/main`, the drill writes `baseline-eligible` and the workflow uploads the file as `failover-drill-baseline` for 90 days even if an extended scenario fails. A `workflow_dispatch` on any other ref still runs the scenarios and does not write that marker, so it cannot replace the main baseline. Pull requests look at the nightly workflow first and still accept a baseline published by the old E2E job.

`nightly_gate` fails when any scenario fails. That failure turns the nightly job red and does not fail a pull request, because no pull-request workflow depends on this one. The job then opens or updates the single open issue titled `[DR] Nightly failover drill` (`<!-- nightly-failover-drill -->`) and includes the rendered report. A later passing run closes that issue. Extra open issues with the same title are closed.

The nightly document and the two extended scenarios are labeled `local mock (containers + Toxiproxy), not real Aurora Global`. `primary-kill` keeps the container fault-injection label from ADR 0001, including when it is the baseline inside the nightly report.

## How the extended faults are injected

`docker-compose.failover-drill-extended.yml` is loaded only for the extended scenarios. Toxiproxy `2.12.0` listens as `postgres-us.proxy` and forwards to `postgres-us.direct`. The EU application's failover host pattern is `?.proxy:5432`, so the writer id `postgres-us` is dialed through the proxy and `postgres-eu` is dialed at `postgres-eu.proxy`, an alias of the EU database. The US application is left on the direct same-region connection.

`network-partition` leaves the proxy enabled with no toxic during warmup. Logical replication from EU subscribes through the proxy. The fault disables the proxy, which resets those connections, and then promotes `postgres-eu` the same way `primary-kill` does. The US database container stays up. `driver_failover_verified` requires the proxy to be disabled, the container to still be running, writer mode on EU, and a recovered write.

`slow-db` adds one downstream latency toxic of `latency_ms` (200 in the checked-in file) with jitter 0 before warmup and before each iteration. Replication for this scenario stays on `postgres-us` directly. The toxic delays the acknowledgement after the commit, so a kill while it is still installed counts those acknowledgements as lost even when the replica is healthy. Immediately before the kill the drill stops starting new writes, removes the toxic, waits until the in-flight writes finish, and waits until two consecutive counts of that iteration's product rows match on both databases. The fault clock starts at that kill, so the drain is not part of RTO. Writers resume after promotion. The recovered writer is `postgres-eu.proxy`, which is not behind the toxic.

Both faults then use the same workload, median, absolute gates, and trace coverage check as `primary-kill`. Extended scenarios do not compare themselves with the `primary-kill` baseline. p99 stays reported and ungated.

## Trade-offs

Publishing the baseline only from the nightly run means a pull request opened before the first nightly after this change still skips regression until an unexpired artifact exists. Keeping the old E2E artifact readable covers the artifacts already stored. Uploading a new baseline from every green push to `main` would let a one-off run replace the nightly numbers; the nightly file is the comparison source instead.

Routing the driver through `?.proxy:5432` is limited to the extended overlay. The pull-request drill keeps `?:5432` and does not start Toxiproxy, so its RTO is not charged an extra proxy hop. That is why the baseline aggregate has to be `primary-kill` measured without the overlay.

Disabling the proxy is the partition. A packet drop inside the database container would also be a partition, and it would not give the slow-db scenario a place to attach a fixed delay. One pinned Toxiproxy does both, and the API calls are deterministic: enable, clear toxics, optional latency, disable.

The slow-db delay is downstream only. Adding the same delay to replication made a lost write likely, and the lost-write gate is zero. Application latency still shows up in the switchover p99. Raising `latency_ms` is a config change; jitter is not an allowed way to widen it.

The nightly job builds the application image itself when the acceptance artifact is absent. It does not need the E2E job, and a red nightly cannot cancel or fail that job. The runner is `ubuntu-latest` with Docker already on the image. No larger hosted runner and no self-hosted runner is required.

A fork pull request still does not get a `workflow_run` comment. This workflow is not triggered by pull requests.

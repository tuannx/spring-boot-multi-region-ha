# Scheduled dynamic takeover measurements

## Run

Use the project's Java 26 toolchain and Gradle:

```sh
gradle -p app test --tests '*ScheduledTakeoverQuickPerfTest' --rerun-tasks
# Full application unit, integration, architecture and performance gate:
gradle -p app clean test
```

Open `app/build/reports/tests/test/index.html`, or inspect `TAKEOVER_PERF`
lines in `app/build/test-results/test/TEST-com.multiregion.queue.performance.ScheduledTakeoverQuickPerfTest.xml`.
The Application E2E workflow uploads these XML/HTML reports as
`application-test-reports`, including when tests fail.

## What is measured

QuickPerf 1.1.0 is a test-only dependency. An explicit
`QuickPerfSqlDataSourceBuilder` proxy instruments the real
`JdbcQueueRegionStateStore.findAll()` against an isolated H2 database. No
Spring Boot 2 starter or production dependency is added.

Each case runs six invocations of the production
`QueueCoordinationScheduler.reconcileTakeoverListeners()` on the real
`ThreadPoolTaskScheduler` constructed by `QueueListenerConfiguration`.
Registration is programmatic with a 10 ms fixed delay to keep the suite fast;
this is not a full Spring application context or the production 60-second
interval. `QueueSchedulingWiringTest` separately guards the annotation's
scheduler name, configurable fixed delay and absence of a fixed rate.

The worker is created inside the QuickPerf recording window. This matters
because QuickPerf's SQL recorder uses an inheritable thread-local; an already
running shared executor could escape measurement. Worker completion is awaited,
failures propagate to JUnit, and each scheduler is cancelled and terminated
before QuickPerf evaluates its SQL budget.

The enforced budget is six SELECT statements and zero INSERT/UPDATE/DELETE
statements per case. Database setup and simulated external health changes use
a separate uninstrumented connection. A recording listener fixture verifies
start/stop counts and active assignment counts after every tick. A logical
clock tests the exact expiration boundary (999 ms versus 1,000 ms) without
waiting for the production 30-minute limit.

`TAKEOVER_PERF` reports median/max elapsed time around reconciliation only,
including JDBC, planning and listener lifecycle calls. Fixed-delay gaps are
measured separately and must be at least 10 ms. QuickPerf's
`@MeasureExecutionTime` additionally reports whole-test time, including fixture
seeding, assertions and scheduler waits; it is not takeover latency.

## Baseline observed locally

2026-09-22, source base `41f3633` plus this test integration, macOS arm64,
OpenJDK 26.0.1, Gradle 9.4.1. Full clean application test run:
**79 tests, zero failures/errors/skips**. No production implementation changed.
Each row represents six ticks, with two region-state rows per queue.

| Case | Queues | SELECTs | Starts / stops | Median tick ms | Max tick ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Healthy | 1 | 6 | 0 / 0 | 0.656 | 0.821 |
| Persistent remote failure | 1 | 6 | 1 / 0 | 0.443 | 0.554 |
| Recovery then new failure | 1 | 6 | 2 / 1 | 0.619 | 0.744 |
| Expiry, suppression, recovery and rearm | 1 | 6 | 2 / 1 | 0.535 | 0.602 |
| Local region fails during takeover | 1 | 6 | 1 / 1 | 0.431 | 0.535 |
| Listener start delayed by 30 ms | 1 | 6 | 1 / 0 | 0.423 | 35.558 |
| Bulk failure then recovery | 1,000 | 6 | 1,000 / 1,000 | 7.206 | 68.440 |

The smallest observed fixed-delay gap was 10.169 ms. Persistent failure did
not recreate listeners. Expired assignments stayed suppressed until a healthy
observation rearmed them. Increasing to 2,000 state rows kept the SELECT count
constant, but the current query still reads all rows and planning/lifecycle work
grows with queue count.

A negative control temporarily changed the expected SELECT count from six to
five for `healthyRegions`. It failed with QuickPerf reporting **six actual
SELECTs**, confirming that scheduler-thread SQL is captured. The six-query
budget was restored before the successful clean run.

These are local regression measurements, not an SLA or a warmed-up benchmark.
There are only six samples per case, no JIT warm-up, and lifecycle logging is
included. Timing is reported rather than constrained by a fragile upper bound.
H2 and recording listeners do not measure PostgreSQL/Aurora network roundtrips,
RabbitMQ connections, message draining, failover propagation or multi-instance
contention. The PostgreSQL-specific startup/upsert paths are outside this
read-only reconciliation measurement. JVM heap allocation on a scheduler
worker is not claimed by this suite. Use the existing Docker acceptance suite
for end-to-end infrastructure proof.

## References

- [QuickPerf project and annotations](https://github.com/quick-perf/quickperf)
- [QuickPerf SQL recorder thread inheritance](https://github.com/quick-perf/quickperf/blob/master/sql/sql-annotations/src/main/java/org/quickperf/sql/SqlRecorderRegistry.java)
- [QuickPerf execution-time measurement limitations](https://github.com/quick-perf/quickperf/blob/master/core/src/main/java/org/quickperf/annotation/MeasureExecutionTime.java)

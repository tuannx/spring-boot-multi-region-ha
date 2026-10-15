# Design decisions

Each entry gives the decision, the reason, what was rejected, and the evidence. Entries marked
**not verified** are design intent that the POC has not tested yet.

## Google RTB constraints (verified against the official pages)

Sources, checked during this work:

- Authorized Buyers RTB peer guide: https://developers.google.com/authorized-buyers/rtb/peer-guide
- Authorized Buyers RTB start page: https://developers.google.com/authorized-buyers/rtb/start

What they say:

| Topic | Statement | Used in the POC |
|---|---|---|
| Deadline | The deadline is `BidRequest.tmax`. Google waits 80 to 1000 ms, depending on format and auction type. | `DeadlineBudget` reads `tmax`, clamps it to a maximum, and subtracts a safety margin. |
| Target | 85% of responses must arrive within the deadline. Bidders that cannot achieve this consistently are throttled. | The loadgen reports `late` (past deadline) separately from `error`. The 85% line is the success threshold. |
| Headroom | "We recommend targeting a total time well below the deadline." | Default `tmax` is 100 ms, with a 10 ms margin. |
| Locations | Four trading locations: NA East, NA West, Europe, APAC. To receive all impressions, servers must be reachable from all of them. | **Not implemented.** One region only. See the AWS target design in `architecture.md`. |
| Format | JSON and Protobuf are both supported. The start page does not recommend either. | The bidder accepts both. The codec benchmark measures the difference. |

What the pages do **not** say. These claims must come from our own measurements, not from Google:

- Protobuf is faster or smaller than JSON.
- Keep-alive or connection reuse matters, or what a new connection costs.

An earlier note in this project said "no more than 2% over the deadline". That was wrong. The
official figure is 85% within the deadline.

## Decisions

### 1. OpenRTB bindings generated from the IAB spec, pinned and vendored

- **Decision:** `openrtb-proto` generates Java from a vendored copy of IAB `openrtb.proto`
  (tag 2.6-202606, commit 1debba8). The vendored file's sha256 is `96ffe8316e4b0db87113a8473c6ebc4f94517b533a739a72d304b62b91b62c24`. `protoc` comes from Maven Central.
- **Why:** The spec's own protobuf file is the source of truth. Generated classes stay in step
  with the spec, and hand-written models drift.
- **Rejected:** Hand-written POJOs with Jackson. More flexible, but every field is a chance to
  diverge from the spec.
- **Watch:** The IAB repo is updated monthly (`2.6-YYYYMM`). Upgrading means updating the
  vendored file and rerunning the tests.

### 2. Protobuf and JSON both accepted; the response uses the request's encoding

- **Decision:** `POST /bid` takes `application/x-protobuf` or `application/json`.
- **Why:** Google accepts both. Protobuf is the lower-cost option, and JSON is what most
  integrations start with. Supporting both lets us measure the difference.
- **Evidence:** `docs/benchmarks.md`, codec section.
- **Known limit:** `JsonFormat` in protobuf-java-util 4.35.1 escapes `=`, `&`, `<`, `>` and `'`
  as `\u00xx` in every string. That is valid JSON, but it inflates the payload. There is no
  switch to disable it in this version. The JSON numbers therefore overstate the cost of a
  typical JSON encoder. Read the codec result with that in mind.

### 3. Deadline budget from `tmax`; late answers become 204

- **Decision:** The bidder computes `budget = min(tmax, maxTmax) - safetyMargin` (minimum 1 ms).
  If the decision finishes after the budget, it returns 204 and counts `bidder.bid.outcome{deadline}`.
- **Why:** A late bid is worth nothing, and the exchange counts it against the 85% target.
  Returning 204 costs nothing and keeps the exchange's statistics clean.
- **Evidence:** The cold-start run. The first request on a cold JVM took 311 ms against a 90 ms
  budget and was correctly dropped. It was not sent late.
- **Consequence for benchmarks:** Warm-up is required. The loadgen sends warm-up requests and
  excludes them from the statistics.

### 4. Billing is idempotent on `bid_id`

- **Decision:** `billing_events.bid_id` is the primary key. The insert is
  `ON CONFLICT (bid_id) DO NOTHING`. A repeated burl returns 200 with no new row.
- **Why:** Exchanges retry notices when they get no response. A failover replays in-flight
  requests. Without idempotency, each retry is a second charge.
- **Evidence:** `docs/benchmarks.md`, billing reconciliation. The loadgen sends about 5% of
  acknowledged notices a second time. In each run cited in `docs/benchmarks.md`, ledger rows equal distinct billed bids, and
  duplicate sends all return 200.
- **Retry behaviour, partly measured:** A billing notice that gets no response
  has an unknown outcome. The database may have committed it. A retry resolves that, because the
  insert is idempotent. Failed notices can still be committed even after retries: one superseded run
  committed 11 notices that failed after three attempts. Retries do not close the gap on their own.
  See `docs/benchmarks.md`, section 5.

### 5. Budget checks use a local view, refreshed from the ledger

- **Decision:** `BudgetCache` holds remaining micro-USD per campaign. The bid path reads only this
  cache. `BudgetSync` reloads the per-campaign totals from `campaign_totals` (one row per campaign) every 500 ms. Each accepted burl also
  lowers the local value at once.
- **Why:** The bid path never waits on the database. A synchronous decrement per bid would put a
  hot row on every campaign and add a database round trip to every bid.
- **Rejected:** Synchronous `UPDATE ... SET remaining = remaining - x` per bid. Exact, but it
  serializes bids through one row. A Redis counter would be faster, but it adds a component.
- **Trade-off, bounded overspend:** An instance cannot see spend that other instances recorded
  in the last refresh interval. Worked example, using the POC's numbers as an estimate: at 300
  bids per second with a 50% win rate, each instance records about 150 wins per second. Over
  0.5 s that is 75 impressions. At $4.50 CPM, that is about $0.34 per instance per interval. Two
  instances overshoot by roughly $0.68 at most on one campaign in the worst case, against a
  $1000 budget. This is arithmetic, not a measurement.
- **Not handled:** Concurrent bids can all pass the check before any of them applies a spend.
  The overshoot from that is bounded by the number of in-flight bids times one impression.

### 6. Failure behaviour: fail open on bids, fail closed on billing

- **Decision:** If the ledger read fails, bidding continues from the last good view, and
  `bidder.budget.refresh.failures` increments. If the billing insert fails, `/burl` returns 503
  with `Retry-After: 1`, so the exchange retries.
- **Why:** Bid availability matters more than exact pacing for a short outage. Billing has to
  be correct, so an unrecorded notice is retried rather than dropped.
- **Evidence:** **Not verified.** The POC has no test for a database outage yet.

### 7. Health: readiness does not depend on the database

- **Decision:** `/actuator/health/readiness` reports the application's state, not the database's.
  HAProxy uses this endpoint for its checks.
- **Why:** If the database went down and readiness failed, every bidder would leave the rotation
  at once. Bidding would stop completely, even though the fail-open rule above would keep it
  going.
- **Evidence:** **Not verified.** Needs a database-outage test.

### 8. Load balancer retries on connection failure

- **Decision:** HAProxy uses `retry-on conn-failure empty-response response-timeout 503` and
  `option redispatch`. Health checks run at `inter 500ms fall 2 rise 2`.
- **Why:** A health check takes about one second to notice a dead instance. Within that window, a
  connection to the dead instance fails straight away, and the retry sends the request to the
  other instance. The client never waits for the health check.
- **Evidence:** `docs/benchmarks.md`, failover drill. A SIGKILL of one bidder under 300 rps
  produced 0 to 2 client-visible errors out of 18,000 requests.
- **Known effect:** A connect attempt to the dead instance can wait for the connect timeout
  before it fails. That is the likely cause of the ~300 ms latency spike in the drill. It is not yet
  confirmed against per-request logs.

### 9. Re-joining instances need a slow start

- **Decision:** HAProxy `slowstart 30s` on both servers. A restarted instance gets its traffic share
  back over 30 s, not at once.
- **Why:** In the first drill, the restarted instance produced late responses for about two seconds
  after it rejoined (13 and 5 late out of 300 requests per second, at seconds 48 and 49). The likely
  cause is a cold JVM taking its full share of traffic before it has warmed up. That is a hypothesis.
- **Evidence:** `docs/benchmarks.md`, section 4. The unhealthy window after the restart fell from 2–3 s
  to about 1 s. The late-count difference (265 and 282 against 296 and 293) is within noise. Two runs
  per side are not enough to claim it.

### 10. Other choices

- **Virtual threads** (`spring.threads.virtual.enabled`): one blocked JDBC call does not hold a
  platform thread. **Not benchmarked separately.**
- **Graceful shutdown** (`server.shutdown=graceful`, 20 s phase timeout). **Not verified** under
  load.
- **Money as `long` micro-USD:** No floating-point drift in the ledger. The CPM is converted once
  per impression, at `round(cpm * 1000)`.
- **Campaigns in YAML:** For the POC. A production bidder reads campaigns from a campaign service.

## Known gaps: must be fixed before any production use

1. **`/burl` does not verify the bid.** The notice is accepted for any `bid` value that is not
   yet in the ledger. A caller who can reach `/burl` can create billing rows and spend budget.
   Production needs signed bid tokens (HMAC over bid id, campaign, price cap and expiry), checked
   on every notice.
2. **`bid.exp` is set but not enforced.** A notice that arrives after the bid's expiry should be
   rejected.
3. **No TLS.** The POC runs on plain HTTP inside a private network. Production terminates TLS at
   the load balancer.
4. **No targeting, frequency capping, pacing, PMP or DOOH.** Not in scope for the POC.
5. **No tracing.** Metrics only. No OpenTelemetry or Datadog.
6. **Single region.** The multi-location requirement above is not implemented.
7. **Benchmarks run on 2 CPUs, shared.** The bidders, PostgreSQL, HAProxy and the loadgen all
   compete for the same two CPUs. Absolute numbers are not comparable with production hardware.
   Only the relative comparisons are meaningful.
8. **Failed notices can still be committed.** A notice can fail on the loadgen side and still be in the
   ledger, even after retries (`docs/benchmarks.md`, section 5). The cause is not classified, and the
   POC has no reconciliation against the exchange's own count. Production needs the cause found and
   a reconciliation.

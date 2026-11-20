# ADR 0004: HA routing rules in Java or as xDS data

This record compares the two ways this repository now implements regional failover: the ServiceTalk router in [`cases/servicetalk-router`](../../cases/servicetalk-router/README.md), where the rules are Java, and the control plane in [`cases/xds-control-plane`](../../cases/xds-control-plane/README.md), where the same rules are xDS resources that Envoy and proxyless gRPC read. Both cases land in their own pull requests (#120 and #121). This record assumes both are merged.

## Decision

The ServiceTalk router stays the router that the failover drill measures. The nightly and pull-request drills keep running it unchanged.

The xDS control plane is the reference for where the rules should live. The next step is for the ServiceTalk router to read priority, weight and health from that control plane over EDS, instead of from environment variables. That requires an xDS client for ServiceTalk. This repository has none, and this record does not add one.

Envoy as the edge is not adopted yet. It has only been validated with `envoy --mode validate`, not run against the control plane, and the drill has no Envoy scenario.

Proxyless gRPC is not adopted. `app-us` and `app-eu` serve HTTP/JSON, not gRPC, so the gRPC test uses its own test backends.

## The same rule, two ways

| Rule | ServiceTalk router (Java) | xDS resource | How it changes at runtime |
| :--- | :--- | :--- | :--- |
| US first, EU when US has no healthy host | `LocalityPriorityRouter`. Priority per region is fixed in `RouterEndpoints`; hosts come from `*_ENDPOINT_HOST`. | EDS locality `priority` 0 and 1 | Router: a code or env change and a restart. xDS: `POST /drain` or `/restore`. |
| Weighted split, such as 90/10 | Not supported | EDS `load_balancing_weight` | xDS: `POST /weights`. |
| Active health check | `ActiveHealthChecker`, `/actuator/health`, 1s, 2 fails, 2 passes | Envoy cluster `health_checks` with the same values | Both: config only. gRPC does not run these probes (see Trade-offs). |
| Outlier ejection, 3 failures, 5s growing to 5 min | `RegionEndpoint` | `outlier_detection` on the Envoy and gRPC clusters | Both: config only. |
| Retry budget 20%, minimum 3 | `RetryBudget` | `circuit_breakers.thresholds.retry_budget` | Both: config only. |
| Never replay a request that may have reached the backend, unless the method is idempotent | `RetryPolicy` | Envoy route on `:method` for `GET`, `HEAD`, `OPTIONS`, `TRACE`, `PUT`, `DELETE` retries `connect-failure,reset,gateway-error`. Every other method retries `connect-failure` only. | Both: config only. |
| Retry in the other region | Next region in the ordered list | `retry_priority: previous_priorities` and `previous_hosts` | Both: config only. |
| Per-try 800ms, whole route 1.6s | `RouterPolicy` | Envoy `per_try_timeout` and route `timeout` | Both: config only. |

The Java side is about 720 lines across the six router classes, and this repository owns and tests every branch of them. The xDS side is about 300 lines that build resources (`EnvoyResources`, `GrpcResources`, `EndpointResources`). The behaviour behind those resources is Envoy's and gRPC's code, not this repository's. Those line counts compare what this repository maintains, not how much logic runs.

## What was measured

All numbers are local runs against mock or test backends, not the CI runner and not real regions.

ServiceTalk router, from `RouterSafeRetryTest` and `RouterHealthCheckTest` (per-try timeout 800ms):

| Case | Before #120 | After #120 |
| :--- | :--- | :--- |
| `POST` reaches US, is written, answers after 1200ms | Replayed in EU, written twice | `504` with `X-Retry-Skipped: unsafe-method`, written once |
| US hangs, no user traffic | Detected only after users failed | Probes mark US unhealthy in about 2.0s |
| 50 concurrent `GET`s after detection | All 50 at 700ms or more | 0 of 50 at 700ms or more, slowest 57ms |
| 50 concurrent `GET`s after the old 5s cooldown, US still hung | All 50 at 700ms or more again | 0 of 50 at 700ms or more |
| US comes back | Not measured | Back in about 1.3s, after two passing probes |
| 50 concurrent `GET`s at the moment US hangs | Not measured | Default budget: 21 retried in EU, 29 answered `504` with `X-Retry-Skipped: retry-budget`. Budget 100%: all 50 retried. |

xDS control plane, from `ProxylessGrpcFailoverTest` (three independent `xds:///products` channels, 300 calls per phase):

| Phase published | Every client |
| :--- | :--- |
| US priority 0, EU priority 1 | 100% US |
| US unhealthy | 100% EU |
| US healthy again | 100% US |
| Both priority 0, weights 90/10 | US share between 80% and 97% |

Each phase converged within one or two batches. The test allows 10 seconds. The time from publish to the last client switching was not measured more precisely than that. In one of five runs, one call got no answer within 2 seconds although its deadline was 500ms. The test counts such calls instead of failing on them, and the cause was not found.

Envoy: the snapshot is checked by `EnvoyResourcesTest`, and CI validates the bootstrap. No request has gone through Envoy in this repository.

## Why xDS

1. One decision reaches every client. The gRPC test changes three clients with one publish. With the router, each router instance reads its own environment, so two instances can disagree until both restart.
2. Traffic moves without a deploy. Draining a region or setting 90/10 is one HTTP call to the control plane. The router has no weighted split at all.
3. The rules are reviewed as data. The retry rule that #120 had to fix in `RetryPolicy` is, on the Envoy side, a route match that `EnvoyResourcesTest` can assert.
4. It is the interface Envoy, Istio and gRPC already share. A ServiceTalk client that speaks it could join the same control plane as those.

## Trade-offs

The control plane is a new dependency on the routing path. xDS clients are expected to keep their last accepted configuration when the control plane is unreachable. This repository has not tested that.

Health is split. Envoy runs active health checks itself. Proxyless gRPC does not run Envoy's `health_checks`; it uses the health status that EDS publishes, plus outlier detection on real calls. This control plane publishes health only when someone calls `/drain` or `/restore`. It has no prober, so a hung region stays published as healthy for gRPC clients until outlier detection ejects it. A production control plane would need its own probe, or Envoy in front.

The gRPC snapshot sets no retry policy. Retries on the gRPC side in this case are only what the client does by default.

Envoy EDS endpoints must be IP addresses. The control plane resolves the Compose names and republishes every 5 seconds when an address changes, so a restarted container can be unreachable for up to that long after it gets a new IP.

Debugging moves out of the repository. A wrong failover in the router is a stack trace in this code. A wrong failover through xDS is a question of which snapshot version each client accepted, read from Envoy's admin `/config_dump` or gRPC's xDS status.

The Java router is more code to own, but every branch is tested here and its behaviour can be changed in one pull request. The idempotent-retry fix in #120 is the example: the bug and the fix are both in this repository.

## Not decided here

The design of an xDS client for ServiceTalk: which resources it reads first (EDS only, or CDS as well), and how priority and weight reach ServiceTalk's load balancer. That is upstream work in `apple/servicetalk` and needs its own record.

Running the drill through Envoy. That needs an Envoy scenario in the drill and a measured run before Envoy can be compared with the router on RTO and p99.

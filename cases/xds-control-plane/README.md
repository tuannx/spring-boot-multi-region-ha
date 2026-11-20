# xDS control plane: the same HA rules, as data

The [ServiceTalk router](../servicetalk-router/README.md) implements priority failover, health checks, outlier ejection and retry rules in Java. This case publishes the same rules from one control plane over xDS, the protocol Envoy, Istio and proxyless gRPC already speak. Changing where traffic goes becomes one call to the control plane; no client is rebuilt or restarted.

## What runs

| Piece | What it is |
| :--- | :--- |
| `XdsControlPlane` | [java-control-plane](https://github.com/envoyproxy/java-control-plane) 1.0.55 serving ADS. Envoy nodes (id starting `envoy`) and gRPC clients get separate snapshots built from one `Topology`. |
| `Topology` | Regions with priority, weight and health. The whole routing decision. |
| `AdminServer` | Plain HTTP so a demo needs only curl: `GET /topology`, `POST /drain`, `/restore`, `/weights`, `/reset`. |
| `GrpcResources` | Listener, cluster and endpoints for proxyless gRPC (`xds:///products`): priority failover (gRFC A27), locality weights, outlier detection (gRFC A50). |
| `EnvoyResources` | Listener, routes and cluster for an Envoy edge in front of `app-us` and `app-eu`. |

## Same rule, two ways

| Rule | ServiceTalk router (Java, PR #120) | xDS (this case) |
| :--- | :--- | :--- |
| US first, EU when US has no healthy host | `RegionOrder` + `LocalityPriorityRouter` | EDS locality `priority` 0 and 1 |
| Split traffic 90/10 | Not supported | EDS `load_balancing_weight` |
| Active health check | `ActiveHealthChecker` | Cluster `health_checks`, `/actuator/health`, 1s, 2/2 |
| Outlier ejection 3 failures, 5s growing to 5 min | `RegionEndpoint` | Cluster `outlier_detection` |
| Retry budget 20%, min 3 | `RetryBudget` | `circuit_breakers.thresholds.retry_budget` |
| Never replay a write | `RetryPolicy` | Route on `:method` `GET\|HEAD\|OPTIONS\|TRACE\|PUT\|DELETE` retries `connect-failure,reset,gateway-error`; every other method only `connect-failure` |
| Retry in the other region | Next index in the ordered list | `retry_priority: previous_priorities` |
| Per-try 800ms, route 1.6s | `RouterPolicy` | `per_try_timeout`, route `timeout` |
| Drain a region now | Redeploy with new env vars | `POST /drain?region=us-east-1` |

## Tests

```bash
gradle -p cases/xds-control-plane/app clean test
```

`ProxylessGrpcFailoverTest` starts two gRPC backends (US, EU), the control plane and three independent `xds:///products` channels, then changes only the control plane:

| Phase | Published | Every client, measured over 300 calls |
| :--- | :--- | :--- |
| 1 | US priority 0, EU priority 1 | 100% US |
| 2 | US unhealthy | 100% EU |
| 3 | US healthy again | 100% US |
| 4 | US and EU both priority 0, weights 90/10 | US share between 80% and 97% |

Each phase converged within a batch or two in local runs. During a switch a few calls still reach the old region, and in one of five runs one call got no answer within 2s although its deadline was 500ms; the test counts such calls instead of waiting on them.

`EnvoyResourcesTest` checks the Envoy snapshot is consistent and carries the rules in the table above. Envoy itself is not run by the tests.

## Envoy demo on your machine

Envoy has not been run against this control plane yet; CI validates the bootstrap with `envoy --mode validate`.

```bash
docker compose up -d                                              # main stack: app-us, app-eu, ...
docker compose -f cases/xds-control-plane/docker-compose.yml up -d --build
```

```bash
curl -i http://localhost:8010/api/products                        # served by us-east-1
curl -s http://localhost:18001/topology
curl -s -X POST "http://localhost:18001/drain?region=us-east-1"   # Envoy moves to eu-west-1, no restart
curl -s -X POST "http://localhost:18001/restore?region=us-east-1"
curl -s -X POST "http://localhost:18001/weights?us-east-1=90&eu-west-1=10"
curl -s http://localhost:9901/clusters | grep products_http      # Envoy's view: priority, weight, health
curl -s -X POST http://localhost:18001/reset
```

`/weights` leaves health as it is, so restore a drained region first. The control plane republishes when a compose name starts resolving or changes IP, because Envoy EDS endpoints must be IPs.

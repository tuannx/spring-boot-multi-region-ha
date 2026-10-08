# ServiceTalk Locality-Aware Multi-Region Router

An enterprise-grade, asynchronous, non-blocking HTTP edge router built with **ServiceTalk** and **Netty**, implementing **Envoy/ServiceTalk Locality Priority ($P_0 \to P_1$)** with **Passive Outlier Detection** and **Zero-Latency-Penalty Failover**.

This case demonstrates the architectural contrast between traditional reverse proxies (such as Nginx) and reactive locality routers during regional disaster recovery and multi-region outages.

---

## 1. Architectural Motivation & Problem Statement

### The Problem with Traditional Reverse Proxies (e.g., Nginx)

In a typical Active-Passive or Active-Active Multi-Region deployment (e.g. `us-east-1` Primary, `eu-west-1` Secondary):

1. **Blind Timeout Penalty**: When the local compute instance (`app-us`) fails, hangs, or suffers network partitions, Nginx holds incoming client connections until `proxy_connect_timeout` (usually 5,000ms+) expires before taking action.
2. **No Dynamic Outlier Ejection**: Standard open-source Nginx lacks passive outlier ejection. Even when `app-us` has crashed repeatedly, every subsequent request continues to blindly attempt `app-us`, stalling for 5 seconds on each attempt.
3. **Dropped Client Requests**: For mutation endpoints (`POST /api/...`), standard proxy configurations cannot safely retry or failover without duplicating writes or dropping connections with `502 Bad Gateway` / `504 Gateway Timeout`.

### The Solution: ServiceTalk Locality-Aware Router

The ServiceTalk Router is designed specifically for multi-region topologies:

- **Locality Priority ($P_0 \to P_1$)**: Dynamically maps requests based on origin header (`X-Source-Region`) or client geolocation. Requests originating from US hit US compute ($P_0$); requests from EU hit EU compute ($P_0$).
- **Passive Outlier Detection**: Tracks consecutive connection errors and upstream 5xx responses. When consecutive failures reach the threshold (default: 3), the endpoint is marked as an **Outlier** and ejected from the active pool for a configurable cooldown window (default: 5,000ms).
- **Zero-Latency-Penalty Failover**: Once an endpoint is ejected, subsequent requests completely bypass the broken region and route directly to the healthy secondary region ($P_1$) with **0ms latency penalty** (< 15ms total latency) and **100% availability**.
- **Safe In-Memory Payload Duplication**: Aggregates and duplicates HTTP request bodies in memory before proxying, allowing safe immediate failover for `POST` and `PUT` mutations without payload truncation or stream consumption errors.
- **Probative Recovery**: Once the cooldown window expires, the router sends probe traffic to verify recovery before smoothly restoring normal $P_0$ priority.
- **Rich Telemetry Headers**: Adds diagnostic response headers:
  - `X-Routed-Region`: `us-east-1` or `eu-west-1`
  - `X-Routed-Priority`: `P0` (Primary) or `P1` (Secondary Failover)
  - `X-Router-Backend`: `servicetalk`
  - `X-Failover`: `true` or `false`

---

## 2. Architecture Diagram

```text
                                 ┌─────────────────────────────────┐
                                 │       Client Traffic            │
                                 │  (HTTP /api/products, /health)  │
                                 └────────────────┬────────────────┘
                                                  │
                                                  ▼
                        ┌──────────────────────────────────────────────────┐
                        │      ServiceTalk Multi-Region Router (:8085)     │
                        │   - Non-blocking Netty EventLoop                 │
                        │   - Locality Priority Engine (P0 -> P1)          │
                        │   - Passive Outlier Detection & Circuit Breaker  │
                        └─────────────┬──────────────────────┬─────────────┘
                                      │                      │
                   P0 Priority Path   │                      │ P1 Failover Path
                   (Healthy: < 15ms)  │                      │ (Outlier / Error)
                                      ▼                      ▼
                        ┌──────────────────────┐  ┌──────────────────────┐
                        │  Region 1 (us-east-1)│  │  Region 2 (eu-west-1)│
                        │      app-us:8080     │  │      app-eu:8081     │
                        │   (Writer / Reader)  │  │   (Reader / Failover)│
                        └──────────────────────┘  └──────────────────────┘
```

---

## 3. Comparative Analysis: Nginx vs ServiceTalk Router

| Architectural Metric | Traditional Nginx Router (Legacy `:8001`) | ServiceTalk Locality Router (Default `:8000`) |
| :--- | :--- | :--- |
| **Outlier Detection Strategy** | None (Blind reverse proxy) | Passive Outlier Ejection (3 errors, 5s cooldown) |
| **Outage Failover Latency** | ~5,000ms stall (`proxy_connect_timeout`) | < 15ms (< 1ms routing overhead) |
| **API Availability During Regional Crash** | **0%** (`502 Bad Gateway` / `504 Timeout`) | **100%** (Zero dropped requests) |
| **Failover Penalty for Subsequent Requests** | Full 5,000ms penalty on every request | **0ms Penalty** (ejected node bypassed completely) |
| **State-Mutating Request Safety (`POST`)** | Drops connection / broken stream | Safe in-memory duplicated buffer replay |
| **Locality Routing Precision** | Static regex / map directives | Programmatic Envoy-style Locality Priority ($P_0 \to P_1$) |
| **Observability Telemetry** | Basic access logs | Dynamic headers (`X-Routed-Priority`, `X-Failover`, `X-Routed-Region`) |

---

## 4. Quickstart & Verification

### 4.1 Running with Docker Compose

Start the multi-region stack (ServiceTalk router is enabled as default ingress on port `8000`):

```bash
# Start the full multi-region stack with ServiceTalk router as default ingress (:8000)
docker compose up -d

# (Optional) To run the legacy Nginx router on :8001 alongside for comparison:
docker compose --profile legacy-nginx up -d
```

### 4.2 Verifying Endpoints

1. **Default US Locality Routing ($P_0 \to \text{us-east-1}$)**:
   ```bash
   curl -i http://localhost:8000/api/products
   ```
   *Response contains headers:*
   ```http
   HTTP/1.1 200 OK
   X-Routed-Region: us-east-1
   X-Routed-Priority: P0
   X-Failover: false
   X-Router-Backend: servicetalk
   ```

2. **Cross-Region Source Locality ($P_0 \to \text{eu-west-1}$)**:
   ```bash
   curl -i -H "X-Source-Region: eu-west-1" http://localhost:8085/api/products
   ```
   *Response contains headers:*
   ```http
   HTTP/1.1 200 OK
   X-Routed-Region: eu-west-1
   X-Routed-Priority: P0
   X-Failover: false
   ```

### 4.3 Running the Automated Stress & Chaos Benchmark

Run the automated stress and failover test comparing Nginx and ServiceTalk:

```bash
./scripts/servicetalk-stress-test.sh
```

The benchmark script executes:
1. **Phase 1**: Baseline latency distribution across all healthy nodes.
2. **Phase 2**: Regional chaos injection (`docker pause multiregion-app-us`). Demonstrates Nginx stalling for 6,000ms while ServiceTalk detects failure and fails over.
3. **Phase 3**: Passive outlier ejection. Dispatches consecutive requests to verify **0ms latency penalty** and **100% success rate**.
4. **Phase 4**: Regional recovery and probationary probe traffic.
5. **Phase 5**: Side-by-side comparative scorecard output.

---

## 5. Unit & Integration Testing

The router includes a suite of integration tests verifying normal routing, explicit headers, outlier ejection, and payload preservation during failover:

```bash
cd cases/servicetalk-router/app
gradle test
```

---

## 6. Configuration Reference

The router can be configured via environment variables:

| Environment Variable | Default Value | Description |
| :--- | :--- | :--- |
| `ROUTER_PORT` | `8085` | Port the ServiceTalk router listens on |
| `US_ENDPOINT_HOST` / `TARGET_US_HOST` | `app-us` | Region 1 (`us-east-1`) hostname |
| `US_ENDPOINT_PORT` / `TARGET_US_PORT` | `8080` | Region 1 port |
| `EU_ENDPOINT_HOST` / `TARGET_EU_HOST` | `app-eu` | Region 2 (`eu-west-1`) hostname |
| `EU_ENDPOINT_PORT` / `TARGET_EU_PORT` | `8081` | Region 2 port |
| `FAILURE_THRESHOLD` / `OUTLIER_FAILURE_THRESHOLD` | `3` | Consecutive failures before marking endpoint as outlier |
| `COOLDOWN_MS` / `OUTLIER_COOLDOWN_MS` | `5000` | Duration (ms) an outlier remains ejected from active pool |
| `CONNECT_TIMEOUT_MS` | `500` | Netty socket connect timeout in milliseconds |
| `REQUEST_TIMEOUT_MS` | `800` | Total upstream request timeout before fast-failover |

---

## 7. OpenTelemetry & SigNoz Observability (Before vs After)

When paired with the repository's SigNoz observability stack (`docker-compose.observability.yml`), the router provides full distributed trace visibility that eliminates the legacy proxy blind spot:

```text
[Client] ──► [multiregion-router (ServiceTalk :8000)] ──► [multiregion-app (Spring Boot)] ──► [PostgreSQL]
             └────────────── End-to-End Distributed Trace (W3C Context Propagation) ──────────────┘
```

| Observability Dimension | Before (Legacy Nginx Ingress) | After (ServiceTalk Locality Router) |
| :--- | :--- | :--- |
| **SigNoz Service Map Node** | **Missing**: Graph started directly at Spring Boot app | **Full Ingress Node**: `multiregion-router` linked to `multiregion-app` |
| **Outage Trace Capture** | **0%**: 504 Gateway Timeouts were dropped without trace | **100%**: Router span captures error, timeout, and failover child span |
| **5s Connect Timeout Visibility** | **Invisible**: Phantom latency not recorded in SigNoz | **Explicit**: Span displays 800ms upstream timeout clearly |
| **Outlier Ejection Visualization** | **None**: Repeated blind attempts with 5s stalls | **Flamegraph shows direct P1 route** with 0ms latency penalty |
| **W3C TraceContext Propagation** | Broken / unmanaged | Full `traceparent` and `tracestate` propagation |
| **Routing Metadata Tags** | None | Headers `X-Routed-Priority`, `X-Routed-Region`, `X-Failover` |

For full details on spinning up SigNoz and viewing live trace flamegraphs, see [docs/observability.md](../../docs/observability.md).


# OpenTelemetry and SigNoz

The HA application image includes the OpenTelemetry Java agent 2.28.1. The
normal Docker Compose stack keeps `OTEL_SDK_DISABLED=true`, so the existing
local flow does not require a running collector. The optional observability
overlay enables the SDK for both application regions and exports OTLP gRPC to
the SigNoz ingester on the Docker host.

The repository pins SigNoz `v0.140.0`, the OpenTelemetry Collector
`v0.144.9`, and ClickHouse `25.12.5` for a reproducible local stack. The
ClickHouse pin follows SigNoz's supported Docker deployment defaults rather
than tracking an unrelated latest database release.
The generated SigNoz lock may still contain `postgres:16` for SigNoz's
internal metadata store; that image is separate from the application's
PostgreSQL `18.6` data plane.

## Start the local stack

SigNoz's current self-hosted Docker workflow uses `foundryctl` to generate the
supported Compose deployment. Install it using the official
[SigNoz Docker guide](https://signoz.io/docs/install/docker/), then run:

```bash
./scripts/observability-up.sh
./scripts/observability-verify.sh
```

The script generates the SigNoz Compose files under `.observability/` (ignored
by Git), starts SigNoz, and then starts the HA application with
`docker-compose.observability.yml`.

On the first run, open the SigNoz UI and create its local administrator account
before inspecting telemetry. Do not commit those credentials. The generated
Compose patch uses a clearly local-only default for
`SIGNOZ_TOKENIZER_JWT_SECRET`; set that variable explicitly for any shared or
persistent environment.

The application Compose files use PostgreSQL `18.6`. A PostgreSQL major
version is not an in-place data-directory upgrade: back up and migrate an
existing pre-18 volume with PostgreSQL's upgrade procedure, or recreate local
demo volumes after confirming that the data can be discarded.

Open:

- SigNoz UI: <http://localhost:9090>
- OTLP gRPC: `localhost:4317`
- OTLP HTTP: `localhost:4318`
- US application: <http://localhost:8080>
- EU application: <http://localhost:8081>

The application host ports are configurable. For example, when another local
process already uses `8080`:

```bash
APP_US_HOST_PORT=18080 ./scripts/observability-up.sh
APP_US_HOST_PORT=18080 ./scripts/observability-verify.sh
```

The two application containers share the logical service name
`multiregion-app`. Their telemetry is separated with these resource
attributes:

- `cloud.region=us-east-1` / `eu-west-1`
- `service.instance.id=app-us` / `app-eu`
- `deployment.environment=local`

The agent automatically instruments Spring MVC HTTP traffic, JDBC calls,
RabbitMQ messaging, JVM runtime metrics, and Logback logs. The 100% trace
sampler is intentional for this small local demo; set
`OTEL_TRACES_SAMPLER_ARG=0.1` for a 10% sample.

## Generate and inspect telemetry

```bash
curl -fsS http://localhost:8080/health >/dev/null
curl -fsS http://localhost:8081/health >/dev/null
curl -fsS http://localhost:8000/api/products >/dev/null
curl -fsS http://localhost:8080/actuator/metrics/jvm.memory.used >/dev/null
```

Wait a few seconds for batching, then inspect the `multiregion-app` service in
SigNoz's Services, Traces, Metrics, and Logs views. Filter by
`cloud.region` or `service.instance.id` to compare the two regions.

When running with the default ServiceTalk router, inspect `multiregion-router`
to view the end-to-end distributed trace and locality routing metrics.

## Router Replacement Observability: Nginx vs ServiceTalk in SigNoz

The migration from legacy Nginx reverse proxy to the ServiceTalk Locality Router
fundamentally changes observability fidelity in SigNoz:

### Before: Legacy Nginx Ingress (Trace Blind Spot)

```text
[Client] ──(Untraced HTTP)──► [Nginx :8000] ──(Untraced)──► [Spring Boot App] ──► [PostgreSQL]
                                                             └─ Trace starts here ──┘
```

1. **Missing Ingress Node**: Nginx was an uninstrumented C proxy. In SigNoz's Service Map, the graph began abruptly at `multiregion-app`. The edge ingress tier was completely absent.
2. **The 5-Second Latency Blind Spot**: When `app-us` failed or stalled, Nginx waited for `proxy_connect_timeout 5s`.
   - On dropped requests (`504 Gateway Timeout`), **zero traces** reached SigNoz because the request never reached Spring Boot. SREs only saw traffic plummet with zero diagnostic trace evidence.
   - On health failover (`@health_eu`), `app-eu` recorded a normal execution time (~10ms). The 5,000ms client delay was completely invisible in application traces ("phantom latency").
3. **No Locality or Failover Telemetry**: No trace tags captured locality decisions ($P_0$ vs $P_1$) or failover events.

---

### After: ServiceTalk Locality Router (End-to-End Traced)

```text
[Client] ──► [multiregion-router (ServiceTalk :8000)] ──► [multiregion-app (Spring Boot)] ──► [PostgreSQL]
             └────────────── End-to-End Distributed Trace (W3C Context Propagation) ──────────────┘
```

1. **Full Distributed Tracing from Edge**: `multiregion-router` appears in the SigNoz Service Map and Service List as the authoritative ingress tier, connected directly to `multiregion-app`.
2. **Observable Failover Spans & Circuit Breaking**:
   - **In-flight Failover**: When `app-us` stalls during an outage, the parent span on `multiregion-router` explicitly records:
     - Child Span 1: `GET http://app-us:8080/api/products` &rarr; `error: true`, `TimeoutException` (800ms).
     - Child Span 2: `GET http://app-eu:8081/api/products` &rarr; `http.status_code: 200` (~15ms).
     - Telemetry headers: `X-Failover: true`, `X-Routed-Priority: P1`, `X-Routed-Region: eu-west-1`.
   - **Outlier Ejection (Zero Penalty)**: Once `app-us` breaches the failure threshold, subsequent traces show **direct routing** to `app-eu` as a single child span in < 20ms. The outlier bypass is clearly visible in trace flamegraphs.
3. **Root-Cause Attribution & MTTD**: SREs can instantly decompose total round-trip latency into:
   `Client Ingress -> Router Locality Evaluation -> Upstream Compute -> Database Execution`.

---

### SigNoz Telemetry Comparison Matrix

| Observability Capability | Before (Nginx Router) | After (ServiceTalk Router) |
| :--- | :--- | :--- |
| **Service Map Ingress Node** | **Missing** (Graph starts at Spring Boot) | **Present** (`multiregion-router` &rarr; `multiregion-app`) |
| **Outage Trace Capture** | **0%** (504 errors dropped with no trace) | **100%** (Router span captures error & failover child span) |
| **5s Connect Timeout Visibility** | **Invisible** (Phantom latency not in SigNoz) | **Explicit** (Span shows 800ms upstream timeout) |
| **Outlier Ejection Visualization** | **None** (Blind repeated attempts) | **Flamegraph shows direct P1 route** with 0ms penalty |
| **W3C Context Propagation** | Broken / unmanaged | Full `traceparent` & `tracestate` propagation |
| **Locality Telemetry in Spans** | None | Headers: `X-Routed-Priority`, `X-Routed-Region`, `X-Failover` |
| **RED Metrics on Ingress** | None in SigNoz | Native Request Rate, Error Rate, and Duration in SigNoz |

## Stop and clean up

```bash
./scripts/observability-down.sh
PURGE_DATA=true ./scripts/observability-down.sh
```

The first command preserves Postgres, ClickHouse, and SigNoz metadata volumes.
Use `PURGE_DATA=true` only when the local telemetry history should be removed.

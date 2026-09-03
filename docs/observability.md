# OpenTelemetry and SigNoz

The HA application image includes the OpenTelemetry Java agent 2.28.1. The
normal Docker Compose stack keeps `OTEL_SDK_DISABLED=true`, so the existing
local flow does not require a running collector. The optional observability
overlay enables the SDK for both application regions and exports OTLP gRPC to
the SigNoz ingester on the Docker host.

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

## Stop and clean up

```bash
./scripts/observability-down.sh
PURGE_DATA=true ./scripts/observability-down.sh
```

The first command preserves Postgres, ClickHouse, and SigNoz metadata volumes.
Use `PURGE_DATA=true` only when the local telemetry history should be removed.

# Spring Boot Multi-Region High Availability

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.3-brightgreen)](https://spring.io/projects/spring-boot)
[![AWS JDBC Driver](https://img.shields.io/badge/AWS%20JDBC%20Driver-4.0.1-orange)](https://github.com/awslabs/aws-advanced-jdbc-wrapper)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)](https://www.postgresql.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED)](https://www.docker.com/)

A Spring Boot application demonstrating multi-region high availability using the **AWS Advanced JDBC Wrapper** `failover2` plugin. This project simulates Aurora topology and control-plane state with local PostgreSQL instances, including bounded failover detection, runtime writer routing, nginx request routing, and region-aware health monitoring.

```
                         ┌─────────────────────────────────────────────┐
                         │           nginx-router (port 8000)          │
                         │         Load Balancer + Health Check         │
                         └──────────┬──────────────────────┬───────────┘
                                    │                      │
                    ┌───────────────┘                      └───────────────┐
                    ▼                                                     ▼
        ┌───────────────────────┐                            ┌───────────────────────┐
        │   Region: us-east-1   │                            │   Region: eu-west-1   │
        │   Role: PRIMARY       │                            │   Role: SECONDARY     │
        │   Writer Instance     │                            │   Reader Instance     │
        │                       │                            │                       │
        │  ┌─────────────────┐  │                            │  ┌─────────────────┐  │
        │  │   app-us:8080   │  │                            │  │  app-eu:8080    │  │
        │  │  (Spring Boot)  │  │                            │  │  (Spring Boot)  │  │
        │  └────────┬────────┘  │                            │  └────────┬────────┘  │
        │           │           │                            │           │           │
        │  ┌────────▼────────┐  │                            │  ┌────────▼────────┐  │
        │  │ postgres-us:5432│  │                            │  │ postgres-eu:5432│  │
        │  │  (Primary DB)   │  │                            │  │  (Standby DB)   │  │
        │  └─────────────────┘  │                            │  └─────────────────┘  │
        └───────────────────────┘                            └───────────────────────┘
                                    │                      │
                    ┌───────────────┘                      └───────────────┐
                    │                AWS Aurora Global DB                   │
                    │         (Simulated via mock pg_catalog functions)      │
                    └─────────────────────────────────────────────────────────┘
```

## Features

- **Multi-region topology**: Simulates two AWS regions (us-east-1 and eu-west-1)
- **AWS JDBC Wrapper**: Failover-aware initial writer/reader pools via `failover2`
- **Automatic failover detection**: Secondary region detects primary outage and activates
- **Manual failover**: Admin endpoint for forced failover activation
- **Health monitoring**: Region-aware health checks with topology visibility
- **Dynamic queue listener coordination**: Database-backed DR state lets a healthy brother region take over regional listeners after switchover, then auto-release the lease
- **Docker Compose**: Full stack runs locally with Docker
- **Nginx request routing**: Static source-region routing for the local demo
- **Region-aware config**: Profile-based configuration per region

## Quick Start

### Prerequisites

- Docker & Docker Compose v2
- Java 17+ (for local development)
- curl / httpie (for testing)

### 1. Clone and start

```bash
git clone git@github.com:tuannx/spring-boot-multi-region-ha.git
cd spring-boot-multi-region-ha

# Start all services
docker compose up -d --build

# Wait for services to be healthy (about 30 seconds)
docker compose ps
```

### 2. Verify deployment

```bash
# Check primary region health
curl -s http://localhost:8080/health | jq

# Check secondary region health
curl -s http://localhost:8081/health | jq

# View Aurora cluster topology
curl -s http://localhost:8080/admin/topology | jq

# Check through nginx router
curl -s http://localhost:8000/health | jq
```

**Expected health response (primary):**
```json
{
  "status": "UP",
  "region": "us-east-1",
  "role": "primary",
  "writerNode": "postgres-us",
  "dbConnected": true,
  "active": true
}
```

**Expected health response (secondary):**
```json
{
  "status": "UP",
  "region": "eu-west-1",
  "role": "secondary",
  "writerNode": "postgres-us",
  "dbConnected": true,
  "active": false
}
```

### 3. Seed sample data

```bash
chmod +x scripts/seed-data.sh
./scripts/seed-data.sh
```

### 4. Test CRUD operations

```bash
# List all products (via primary)
curl -s http://localhost:8080/api/products | jq

# List all products (via secondary read replica)
curl -s http://localhost:8081/api/products | jq

# Route through nginx to the compute region matching the request source
curl -s -H "X-Source-Region: eu-west-1" http://localhost:8000/api/products | jq

# Create a product (via primary - write)
curl -s -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"Multi-Region Service","price":199.99}' | jq

# Get single product
curl -s http://localhost:8080/api/products/1 | jq

# Update product
curl -s -X PUT http://localhost:8080/api/products/1 \
  -H "Content-Type: application/json" \
  -d '{"name":"Updated Product","price":24.99}' | jq

# Delete product
curl -s -X DELETE http://localhost:8080/api/products/3
```

## How Multi-Region Failover Works

### Regional Execution Rules

This project models warm-standby multi-region operation, not active-active writes. The default rule is to keep compute close to the source data and move ownership only during DR:

1. **HTTP source region owns request compute**: Route an incoming HTTP request to the app in the same region as the request source whenever that region is healthy. The local nginx demo uses `X-Source-Region` (`us-east-1` or `eu-west-1`) for this routing and defaults to `us-east-1`.
2. **Message source region owns message compute**: A message published to `orders.us-east-1` is consumed by the `us-east-1` app by default; a message published to `orders.eu-west-1` is consumed by the `eu-west-1` app by default.
3. **Readers stay same-region**: Read paths should prefer the reader/data replica in the same region as the app handling the request or message.
4. **Writer follows the sun, but remains single-writer**: Exactly one database is authoritative at a time. Both apps initially use `ACTIVE_WRITER_DB_HOST=postgres-us`. After verified EU promotion, both compute regions route future writes through `FAILOVER_WRITER_DB_HOST=postgres-eu`; restart reconciliation restores the correct route from authoritative topology instead of trusting a local flag alone.
5. **DR switchover enables takeover**: A brother region should only take over queues after DR state says the source region is unavailable or switched over. Queue takeover is a DR lease, not steady-state load balancing.
6. **Takeover auto-returns after 30 minutes**: If DR state does not recover first, the takeover listener is released after `QUEUE_TAKEOVER_MAX_DURATION_MS` (`1800000` ms). Normal ownership returns to the original source region after recovery.

### The AWS Advanced JDBC Wrapper

This project uses the [AWS Advanced JDBC Wrapper](https://github.com/awslabs/aws-advanced-jdbc-wrapper) version 4.0.1, which extends the PostgreSQL JDBC driver with Aurora-aware connection handling:

1. **Topology Discovery**: `pg_catalog.aurora_replica_status()` identifies the current writer and topology.

2. **Bounded primary probe**: The failover monitor uses a small direct PostgreSQL pool rather than entering the wrapper's failover loop; connection, socket, statement, and wrapper failover timeouts are five seconds.

3. **Connection Routing**: Writer connections initially go to `postgres-us`; reader connections remain regional. Promotion changes the application's effective writer route to `postgres-eu` without rebuilding the process.

4. **Failover Handling**:
   - Three consecutive connectivity failures trigger a promotion decision, but unfenced promotion is refused by default.
   - Planned promotion proceeds only when authoritative topology already names the local failover target. `FAILOVER_ALLOW_UNFENCED_PROMOTION=true` is an explicit unsafe demo opt-in.
   - Query timeout, schema, permission, and pool-exhaustion errors do not count as evidence that the primary is unreachable.
   - Promotion is complete only after the database writer postcondition and application traffic-route postcondition both pass.

### Configuration Parameters

| Parameter | Description | Example |
|-----------|-------------|---------|
| `wrapperPlugins` | Comma-separated plugin chain | `failover2,dev` |
| `wrapperDialect` | Wrapper dialect | `pg` |
| `failoverHomeRegion` | Home region for this cluster | `us-east-1` |
| `failoverTimeoutMs` | Maximum wrapper failover loop | `5000` |
| `clusterInstanceHostPattern` | Wildcard pattern for topology discovery | `?:5432` |

### Mock Aurora Functions

Since we're using standard PostgreSQL locally, the project includes mock `pg_catalog` functions that simulate Aurora Global Database behavior:

- `aurora_db_instance_identifier()` → Returns current instance ID
- `aurora_replica_status()` → Returns cluster topology (writer + readers)
- `aurora_is_writer()` → Returns whether current instance is the writer

The US region's init SQL identifies the local node as `postgres-us`; the EU region identifies its local node as `postgres-eu`. Before promotion both report `postgres-us` as writer and `postgres-eu` as reader. A product-table fencing trigger rejects writes whenever a local database is not in writer mode, so the Docker acceptance path proves an old writer cannot continue committing after demotion.

## Testing Failover

### Automated Failover Test

```bash
chmod +x scripts/failover-test.sh
./scripts/failover-test.sh
```

This compatibility command delegates to the canonical fenced E2E. It first
proves that promotion is refused while US still owns authority, fences US,
promotes EU, verifies writes from both compute regions land only in EU, checks
queue takeover/release, restarts EU to verify reconciliation, and removes test
volumes on completion.

### Manual Planned Switchover Simulation

```bash
# 1. Check initial state
curl -s http://localhost:8080/health | jq .role
curl -s http://localhost:8081/health | jq .role

# 2. Fence the old writer (real Aurora does this in its control plane)
docker exec multiregion-us psql -U appuser -d appdb -c \
  "SELECT pg_catalog.set_writer_mode(false);"

# 3. Activate failover on the secondary
curl -s -X POST http://localhost:8081/admin/failover-activate | jq

# 4. Verify topology and a real EU write
curl -s http://localhost:8081/admin/topology | jq
curl -s -X POST http://localhost:8081/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"After switchover","price":42.00}' | jq

# 5. Verify the control state is exactly one writer
docker exec multiregion-us psql -U appuser -d appdb -Atqc \
  "SELECT pg_catalog.aurora_is_writer();" # f
docker exec multiregion-eu psql -U appuser -d appdb -Atqc \
  "SELECT pg_catalog.aurora_is_writer();" # t
```

The local demo has no automatic failback. Run the acceptance command with
`--cleanup` (the default in `configure-failover-infra.sh`) to reset state. Real
disaster promotion still requires an external fencing/quorum decision before a
previously unreachable primary is allowed to rejoin.

## API Reference

### Health Check

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/health` | Region-aware health status |
| GET | `/actuator/health` | Spring Boot Actuator health |

### Product CRUD

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/products` | List all products |
| GET | `/api/products/{id}` | Get product by ID |
| POST | `/api/products` | Create a product |
| PUT | `/api/products/{id}` | Update a product |
| DELETE | `/api/products/{id}` | Delete a product |

### Admin

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/admin/failover-activate` | Force activate failover |
| GET | `/admin/topology` | Get Aurora cluster topology |
| GET | `/admin/queues` | Inspect queue region status and active listener assignments |
| POST | `/admin/queues/{queueName}/{region}/down` | Mark a regional queue down and trigger listener takeover |
| POST | `/admin/queues/{queueName}/{region}/up` | Mark a regional queue healthy and release takeover |

## Dynamic Queue Listener Coordination

The queue module keeps regional queue/DR state in the `queue_region_status` table. Listener ownership is split into local startup listeners and dynamic takeover listeners:

- On startup, an app only starts primary listeners for its own `AWS_REGION`.
- The dynamic coordinator does not start any default listeners. It polls `queue_region_status` every `QUEUE_TAKEOVER_POLL_INTERVAL_MS` (`60000` ms by default).
- `DOWN` means the source region has entered DR switchover/unavailable state for that queue, not normal load balancing.
- If a brother region is `DOWN` while the local region is `UP`, the dynamic coordinator starts takeover listeners for every down brother queue in `queues.names`.
- If the brother region recovers, the dynamic coordinator stops those takeover listeners and normal source-region ownership resumes.
- If the brother region remains down, each takeover lease is automatically released after `QUEUE_TAKEOVER_MAX_DURATION_MS` (`1800000` ms, 30 minutes) and is not re-created until that queue recovers and fails again.

The default listener implementation logs lifecycle events only. To attach a real broker such as SQS, RabbitMQ, or Kafka, provide a Spring bean implementing `QueueListenerProvisioner`.

Docker Compose starts one RabbitMQ broker per region:

| Region | Service | AMQP | Management UI | Main queue | Retry queue | DLQ |
|--------|---------|------|---------------|------------|-------------|-----|
| `us-east-1` | `rabbitmq-us` | `localhost:5672` | `http://localhost:15672` | `orders.us-east-1` | `orders.us-east-1.retry` | `orders.us-east-1.dlq` |
| `eu-west-1` | `rabbitmq-eu` | `localhost:5673` | `http://localhost:15673` | `orders.eu-west-1` | `orders.eu-west-1.retry` | `orders.eu-west-1.dlq` |

Credentials are `appuser` / `apppass`. The apps use `QUEUE_LISTENER_TYPE=rabbit` inside Docker, so assignments start real RabbitMQ listener containers. Outside Docker the default is `logging`, which keeps local development lightweight.

Retry/DLQ defaults:

- Main queue dead-letters rejected messages to `.retry`.
- Retry queue waits `QUEUE_RETRY_DELAY_MS` using RabbitMQ TTL, then routes back to the main queue.
- DLQ queues are declared for terminal routing when a real consumer adds max-attempt handling.
- `QUEUE_VISIBILITY_TIMEOUT_MS` maps to listener receive timeout for the local RabbitMQ adapter; for SQS this is where the same config maps to native visibility timeout.
- During DR switchover, takeover is planned for every queue in `queues.names`, so adding more logical queues makes a brother region take over all down-region queues, not just `orders`.

```bash
# Inspect queue state and running listener assignments
curl -s http://localhost:8080/admin/queues | jq

# Simulate eu-west-1 DR switchover; the admin endpoint also forces an immediate reconcile for test/ops use
curl -s -X POST "http://localhost:8080/admin/queues/orders/eu-west-1/down?reason=broker-unreachable" | jq

# Recover eu-west-1 and release takeover back to source-region ownership
curl -s -X POST "http://localhost:8080/admin/queues/orders/eu-west-1/up?reason=broker-recovered" | jq
```

End-to-end acceptance:

```bash
# Product routing, verified local EU promotion, and RabbitMQ queue takeover
./scripts/e2e-acceptance.sh --start --cleanup --verify-failover

# Queue-only benchmark against an already running stack
./scripts/queue-takeover-acceptance.sh

# Or start/build the stack for the queue-only benchmark
./scripts/queue-takeover-acceptance.sh --start
```

The full E2E verifies region-specific read pools, EU-to-US writer routing,
product cleanup, an actual EU app outage, RabbitMQ listener takeover, and
takeover release. Queue evidence is written as JSON and Markdown under
`reports/queue-takeover/`, including timings, final assignments, and log
snapshots when Docker is available.

## Project Structure

```
app/src/main/java/com/multiregion/
├── MultiRegionApplication.java
├── platform/
│   ├── config/          # Retry and immutable region config
│   ├── database/        # Physical wrapper, probe, admin and promoted-writer pools
│   ├── routing/         # Lazy read/write selection and runtime writer switch
│   ├── failover/
│   │   ├── domain/      # Typed failover and topology results
│   │   ├── application/ # Promotion orchestration and activation state
│   │   ├── port/        # Failover control, monitoring and topology contracts
│   │   ├── jdbc/        # Aurora topology and writer-mode adapter
│   │   ├── routing/     # Failover port adapter for the runtime writer switch
│   │   ├── scheduling/  # Spring lifecycle and health-check adapter
│   │   └── config/      # Spring composition root
│   └── web/             # Health and topology HTTP adapters
├── product/
│   ├── application/     # Product use cases
│   ├── domain/          # Product model
│   ├── port/            # Data-routing contract
│   ├── persistence/     # JPA/JDBC adapters
│   └── web/             # Product HTTP adapter
└── queue/
    ├── domain/          # Queue state and pure takeover planning
    ├── application/     # Listener coordination and management use cases
    ├── port/            # Inbound and outbound contracts
    ├── persistence/     # JDBC queue-state adapter
    ├── rabbitmq/        # RabbitMQ listener/topology adapter
    ├── logging/         # Lightweight local listener adapter
    ├── web/             # Queue administration HTTP adapter
    └── config/          # Spring wiring and scheduling adapter
```

The queue and failover cores have no dependency on Spring, JDBC, RabbitMQ, or
HTTP. ArchUnit tests enforce dependency flow from adapters to
application/ports/domain. Arcade Agent continuously measures package balance
and architecture drift; the reproducible baseline workflow is documented in
[`docs/arcade-agent.md`](docs/arcade-agent.md).

## Configuration Reference

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_HOST` | `localhost` | Database hostname |
| `DB_PORT` | `5432` | Database port |
| `DB_NAME` | `appdb` | Database name |
| `DB_USER` | `appuser` | Database user |
| `DB_PASS` | `apppass` | Database password |
| `AWS_REGION` | `us-east-1` | AWS region identifier |
| `REGION_ROLE` | `primary` | `primary` or `secondary` |
| `FAILOVER_HOME_REGION` | `us-east-1` | Home region for failover |
| `ACTIVE_HOME_FAILOVER_MODE` | `strict-writer` | Failover mode when home active |
| `INACTIVE_HOME_FAILOVER_MODE` | `home-reader-or-writer` | Failover mode when home inactive |
| `GLOBAL_CLUSTER_PATTERNS` | — | Comma-separated host:port for all cluster nodes |
| `CLUSTER_INSTANCE_PATTERN` | — | Wildcard pattern for topology |
| `ACTIVE_WRITER_DB_HOST` | `postgres-us` | Active single-writer database host |
| `ACTIVE_WRITER_DB_PORT` | `5432` | Active single-writer database port |
| `LOCAL_DB_HOST` | Region local | Direct local database host used for promotion control and local authority checks |
| `LOCAL_DB_PORT` | `5432` | Direct local promotion-control database port |
| `FAILOVER_WRITER_DB_HOST` | `postgres-eu` | Authoritative promoted-writer host used by both compute regions after switchover |
| `FAILOVER_WRITER_DB_PORT` | `5432` | Promoted-writer database port |
| `FAILOVER_FAILURE_THRESHOLD` | `3` | Consecutive primary-unreachable probes required before a promotion decision |
| `FAILOVER_ALLOW_UNFENCED_PROMOTION` | `false` | Unsafe demo opt-in; when false, unreachable authority cannot be promoted or trusted during restart reconciliation |
| `APP_PORT` | `8080` | Application HTTP port |
| `QUEUE_POLL_INTERVAL_MS` | `5000` | Local listener reconciliation interval |
| `QUEUE_TAKEOVER_POLL_INTERVAL_MS` | `60000` | Dynamic takeover reconciliation interval |
| `QUEUE_TAKEOVER_MAX_DURATION_MS` | `1800000` | Maximum takeover lease duration before auto-release |
| `QUEUE_RETRY_DELAY_MS` | `5000` | RabbitMQ retry queue delay before routing back to main queue |
| `QUEUE_VISIBILITY_TIMEOUT_MS` | `30000` | Listener receive timeout; maps to native visibility timeout for SQS-style adapters |

### Spring Profiles

- **`region-us`**: Activates `application-region-us.yml` (primary, us-east-1)
- **`region-eu`**: Activates `application-region-eu.yml` (secondary, eu-west-1)

## Architecture Decisions

### Why mock pg_catalog functions?

Real Aurora PostgreSQL exposes `aurora_replica_status()` and related functions natively. Since we're running standard PostgreSQL locally, we simulate these functions to demonstrate how the AWS JDBC Wrapper's topology discovery works without needing an actual Aurora cluster. In production, these functions are provided by Aurora directly.

### Failover detection strategy

The scheduling `FailoverListener` checks the primary every 15 seconds on a
dedicated scheduler and through a bounded direct-probe port. Queue reconciliation
uses a separate two-thread scheduler, so a database probe cannot stall queue
ownership work. `FailoverOrchestrator` owns promotion state without holding a
monitor lock across JDBC I/O. A secondary promotes only when authoritative
topology names its local instance (or the explicitly unsafe opt-in is enabled),
then verifies both the database writer postcondition and promoted traffic route.
On restart, a stale EU writer flag is rejected if topology has returned to US.
A demoted US compute process switches its writes to the promoted EU pool, so
queue and product writes do not continue against the fenced database. Three
connectivity-classified failures trigger a decision, but the safe default
refuses promotion when authority is unreachable; schema/configuration/query
timeouts are also reported without promotion. The same fail-closed rule applies
when a promoted secondary restarts while authority is unreachable; operators
who explicitly enable `FAILOVER_ALLOW_UNFENCED_PROMOTION` choose availability
over split-brain protection for that case. Production still needs a real
consensus/quorum, fencing token or lease, and a production promotion adapter.

### Nginx request routing

Nginx statically maps `X-Source-Region` to the matching compute region and
defaults to US. The E2E verifies the post-switchover route through port 8000
with `X-Source-Region: eu-west-1`. This is not a production traffic director;
global ingress health/failover remains an external control-plane responsibility.

## Development

### Local Development without Docker

```bash
# Prerequisites: PostgreSQL 16 running locally
# Create databases for both regions
createdb -U appuser appdb

# Start US region app
SPRING_PROFILES_ACTIVE=region-us \
DB_HOST=localhost DB_PORT=5432 DB_NAME=appdb \
DB_USER=appuser DB_PASS=apppass \
./gradlew bootRun

# Start EU region app (separate terminal)
SPRING_PROFILES_ACTIVE=region-eu \
DB_HOST=localhost DB_PORT=5433 DB_NAME=appdb \
DB_USER=appuser DB_PASS=apppass \
./gradlew bootRun
```

### Building the JAR

```bash
cd app
./gradlew build -x test
java -jar build/libs/multiregion-app-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=region-us
```

## Related Resources

- [RPO Failure Modes Reference](docs/rpo-failure-modes-reference.md) — 13 warm-standby, active-active, and cross-cutting RPO failure scenarios with detection queries and Spring Boot remediation patterns
- [Test Scenarios](docs/test-scenarios.md) — Timeline-based failover and k6 validation scenarios for the current local stack
- [AWS Advanced JDBC Wrapper](https://github.com/awslabs/aws-advanced-jdbc-wrapper) — The official AWS JDBC wrapper with Aurora failover support
- [AWS Aurora Global Database](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-global-database.html) — Multi-region Aurora architecture
- [Spring Boot Reference](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/) — Official Spring Boot documentation
- [Spring Cloud AWS](https://awspring.io/) — Spring integration with AWS services
- [LocalStack / moto](https://github.com/spulec/moto) — Mock AWS services for testing

## License

MIT

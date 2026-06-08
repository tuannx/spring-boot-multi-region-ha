# Spring Boot Multi-Region High Availability

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.3-brightgreen)](https://spring.io/projects/spring-boot)
[![AWS JDBC Driver](https://img.shields.io/badge/AWS%20JDBC%20Driver-4.0.1-orange)](https://github.com/awslabs/aws-advanced-jdbc-wrapper)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)](https://www.postgresql.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED)](https://www.docker.com/)

A Spring Boot application demonstrating multi-region high availability using the **AWS Advanced JDBC Wrapper** with Global Database (gdb) failover plugin. This project simulates a real Aurora Global Database topology using local PostgreSQL instances, complete with automatic failover detection, load balancing via nginx, and region-aware health monitoring.

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
- **AWS JDBC Wrapper**: Transparent writer/reader routing via `gdbFailover` plugin
- **Automatic failover detection**: Secondary region detects primary outage and activates
- **Manual failover**: Admin endpoint for forced failover activation
- **Health monitoring**: Region-aware health checks with topology visibility
- **Docker Compose**: Full stack runs locally with Docker
- **Nginx load balancing**: Weighted routing with health check fallback
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
  "writerNode": "instance-us-001",
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
  "writerNode": "instance-us-001",
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

### The AWS Advanced JDBC Wrapper

This project uses the [AWS Advanced JDBC Wrapper](https://github.com/awslabs/aws-advanced-jdbc-wrapper) version 4.0.1, which extends the PostgreSQL JDBC driver with Aurora-specific features:

1. **Topology Discovery**: The `gdbFailover` plugin queries `pg_catalog.aurora_replica_status()` to discover all cluster instances and identify the writer node.

2. **Global Database Awareness**: The `global-aurora-pg` dialect enables Global Database topology, understanding cluster instances across regions.

3. **Connection Routing**: Writer connections go to `instance-us-001`; reader connections can go to any available reader.

4. **Failover Handling**:
   - **EFM (Enhanced Failure Monitoring)**: Continuously monitors connection health
   - **GDB Failover**: On writer failure, promotes a new writer based on failover mode configuration
   - **Home Region Awareness**: Routes traffic back to the home region when it recovers

### Configuration Parameters

| Parameter | Description | Example |
|-----------|-------------|---------|
| `wrapperPlugins` | Comma-separated plugin chain | `initialConnection,gdbFailover,efm2` |
| `wrapperDialect` | Aurora dialect | `global-aurora-pg` |
| `failoverHomeRegion` | Home region for this cluster | `us-east-1` |
| `activeHomeFailoverMode` | Failover behavior when home is active | `strict-writer` |
| `inactiveHomeFailoverMode` | Failover behavior when home is inactive | `home-reader-or-writer` |
| `globalClusterInstanceHostPatterns` | Comma-separated host:port for all cluster nodes | `postgres-us:5432,postgres-eu:5432` |
| `clusterInstanceHostPattern` | Wildcard pattern for topology discovery | `postgres-?:5432` |

### Failover Modes

- **`strict-writer`**: Only promotes a writer from the home region (required for Aurora Global DB)
- **`home-reader-or-writer`**: Allows any reader in the home region to become writer
- **`reader-or-writer`**: Any reader in any region can become writer

### Mock Aurora Functions

Since we're using standard PostgreSQL locally, the project includes mock `pg_catalog` functions that simulate Aurora Global Database behavior:

- `aurora_db_instance_identifier()` → Returns current instance ID
- `aurora_replica_status()` → Returns cluster topology (writer + readers)
- `aurora_is_writer()` → Returns whether current instance is the writer

The US region's init SQL declares `instance-us-001` as writer; the EU region's init SQL declares `instance-eu-001` as reader. Both report the same topology, as they would in a real Aurora Global Database.

## Testing Failover

### Automated Failover Test

```bash
chmod +x scripts/failover-test.sh
./scripts/failover-test.sh
```

This script:
1. Checks initial health of both regions
2. Creates a test product
3. Kills the primary (us-east-1) database
4. Verifies secondary detects the outage
5. Activates failover on secondary via admin API
6. Restarts primary and verifies recovery

### Manual Failover Simulation

```bash
# 1. Check initial state
curl -s http://localhost:8080/health | jq .role
curl -s http://localhost:8081/health | jq .role

# 2. Simulate primary outage
docker stop multiregion-us

# 3. Wait for failover detection (15s check interval)
sleep 20

# 4. Check secondary health
curl -s http://localhost:8081/health | jq

# 5. Manually activate failover on secondary
curl -s -X POST http://localhost:8081/admin/failover-activate | jq

# 6. Verify topology
curl -s http://localhost:8081/admin/topology | jq

# 7. Recover primary
docker start multiregion-us
sleep 10

# 8. Check everything is back
curl -s http://localhost:8080/health | jq
curl -s http://localhost:8081/health | jq
curl -s http://localhost:8000/health | jq
```

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

## Project Structure

```
spring-boot-multi-region-ha/
├── app/
│   ├── Dockerfile                    # Multi-stage build
│   ├── build.gradle.kts              # Spring Boot 3.4 + AWS JDBC Wrapper
│   ├── settings.gradle.kts
│   └── src/main/
│       ├── java/com/multiregion/
│       │   ├── MultiRegionApplication.java
│       │   ├── config/
│       │   │   ├── DataSourceConfig.java     # AWS JDBC Wrapper config
│       │   │   ├── FailoverListener.java     # Auto/manual failover
│       │   │   └── MultiRegionConfig.java     # Region-aware beans
│       │   ├── controller/
│       │   │   ├── AdminController.java       # /admin/*
│       │   │   ├── HealthController.java      # /health
│       │   │   └── ProductController.java     # /api/products CRUD
│       │   ├── model/
│       │   │   ├── HealthResponse.java        # Health DTO
│       │   │   └── Product.java               # JPA entity
│       │   ├── repository/
│       │   │   └── ProductRepository.java     # Spring Data JPA
│       │   └── service/
│       │       └── ProductService.java        # Business logic
│       └── resources/
│           ├── application.yml                # Base config
│           ├── application-region-us.yml      # us-east-1 profile
│           └── application-region-eu.yml      # eu-west-1 profile
├── docker/
│   ├── init/
│   │   ├── us/
│   │   │   └── 01-init.sql                   # US mock Aurora functions
│   │   └── eu/
│   │       └── 01-init.sql                    # EU mock Aurora functions
│   └── nginx.conf                             # Load balancer config
├── scripts/
│   ├── failover-test.sh                       # Automated failover test
│   └── seed-data.sh                           # Sample data seeder
├── docker-compose.yml                         # Multi-service orchestration
├── .gitignore
└── README.md
```

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
| `APP_PORT` | `8080` | Application HTTP port |

### Spring Profiles

- **`region-us`**: Activates `application-region-us.yml` (primary, us-east-1)
- **`region-eu`**: Activates `application-region-eu.yml` (secondary, eu-west-1)

## Architecture Decisions

### Why mock pg_catalog functions?

Real Aurora PostgreSQL exposes `aurora_replica_status()` and related functions natively. Since we're running standard PostgreSQL locally, we simulate these functions to demonstrate how the AWS JDBC Wrapper's topology discovery works without needing an actual Aurora cluster. In production, these functions are provided by Aurora directly.

### Failover detection strategy

The `FailoverListener` uses a scheduled task (every 15 seconds) to query `aurora_replica_status()` for the writer node. If the query fails (primary is unreachable), the secondary can auto-activate. This is intentionally simplified for demonstration — production deployments would use a consensus mechanism, health check endpoints, and a proper quorum.

### Nginx load balancing

Nginx provides weighted round-robin load balancing with:
- Primary gets weight 100 (preferred for writes)
- Secondary gets weight 50 (read-only traffic)
- Failed instances are automatically removed from rotation
- Health endpoint fallback routes to secondary if primary is down

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

- [AWS Advanced JDBC Wrapper](https://github.com/awslabs/aws-advanced-jdbc-wrapper) — The official AWS JDBC wrapper with Aurora failover support
- [AWS Aurora Global Database](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/aurora-global-database.html) — Multi-region Aurora architecture
- [Spring Boot Reference](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/) — Official Spring Boot documentation
- [Spring Cloud AWS](https://awspring.io/) — Spring integration with AWS services
- [LocalStack / moto](https://github.com/spulec/moto) — Mock AWS services for testing

## License

MIT

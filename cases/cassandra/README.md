# Cassandra Multi-Region Case

This case demonstrates an active-active Apache Cassandra deployment with a
Spring Boot application in two regions:

```text
                       global router :8100
                    ┌──────────┴──────────┐
                    │                     │
             app-us :8180          app-eu :8181
              local DC               local DC
                    │                     │
       ┌────────────┼────────────┐  ┌─────┼─────────────────┐
       │            │            │  │     │                 │
  cassandra-us-1  us-2         us-3 eu-1 eu-2             eu-3
       └──── us-east-1 / RF=3 ───┘  └── eu-west-1 / RF=3 ──┘
                    ╲                   ╱
                     cross-DC replication
```

It is intentionally separate from the root Aurora/PostgreSQL case. Aurora uses
a fenced single global writer; Cassandra accepts writes in both datacenters and
replicates them across regions.

## Guarantees demonstrated

- Six Cassandra 5.0.9 nodes: three in `us-east-1`, three in `eu-west-1`.
- `NetworkTopologyStrategy` with replication factor 3 in each datacenter.
- Each Spring Boot app names its local datacenter and contacts only local nodes.
- Reads, writes, and lightweight transactions default to `LOCAL_QUORUM` /
  `LOCAL_SERIAL`.
- Losing one Cassandra node in a region still leaves the local quorum
  available.
- A complete region outage is handled by global traffic failover to the other
  app and Cassandra datacenter.
- Writes accepted while a datacenter is offline are delivered after recovery
  through Cassandra's hinted handoff and repair mechanisms.

`LOCAL_QUORUM` is a local consistency contract. Cross-datacenter replication is
asynchronous, so a write can be acknowledged in one region before it is visible
in the other. Concurrent updates to the same primary key also use Cassandra's
timestamp-based last-write-wins reconciliation. A production design must make
that RPO and conflict behavior explicit for each workload.

## Run

Requirements: Docker Compose v2, `curl`, and `jq`.

```bash
./scripts/cassandra-e2e.sh --start --cleanup
```

The acceptance flow:

1. Waits for both apps and all six Cassandra nodes.
2. Writes through US with `LOCAL_QUORUM` and verifies the row appears in EU.
3. Stops the US app and all three US Cassandra nodes.
4. Verifies the global router moves US-source traffic to EU.
5. Writes in EU while US is unavailable.
6. Restarts US and verifies the EU write becomes visible there.

Use `--keep` instead of `--cleanup` to inspect the environment after the run.

```bash
docker compose -p multiregion-cassandra \
  -f cases/cassandra/docker-compose.yml ps

curl -s http://localhost:8180/health | jq
curl -s http://localhost:8181/health | jq

curl -s -X POST http://localhost:8100/api/catalog \
  -H "X-Source-Region: eu-west-1" \
  -H "Content-Type: application/json" \
  -d '{"name":"Regional catalog item","price":49.99}' | jq
```

Ports:

| Port | Service |
|------|---------|
| `8100` | Global nginx router |
| `8180` | US Spring Boot app |
| `8181` | EU Spring Boot app |
| `9042` | US seed Cassandra node |
| `9142` | EU seed Cassandra node |

## Why fail over the application region

The Java driver's default policy is datacenter-local. It does not silently send
`LOCAL_QUORUM` operations to a remote datacenter, because that changes latency,
capacity, and consistency semantics. In this case, nginx stands in for a global
load balancer: when US fails, traffic moves to the EU application, whose driver
is local to the EU Cassandra datacenter.

Production ingress should use health-checked DNS, Anycast, or a global load
balancer. The nginx container is only a deterministic local simulation.

## Source layout

```text
cases/cassandra/
├── app/                    # Independent Spring Boot Cassandra application
├── docker/
│   ├── nginx.conf          # Source-region routing with regional backup
│   └── schema.cql          # Multi-DC keyspace and catalog table
├── docker-compose.yml      # 2 DC x 3 nodes, two apps, router, schema init
└── README.md
```

The app keeps the catalog domain and application service independent of Spring
Data. `SpringDataCassandraCatalogStore` is the persistence adapter behind the
`CatalogStore` port.

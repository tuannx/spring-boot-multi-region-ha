# ElastiCache Global Datastore (Valkey) Multi-Region Case

[![Architecture Map](https://img.shields.io/badge/Architecture_Map-Interactive_Explorer-blue?logo=google-chrome&logoColor=white)](https://tuannx.github.io/spring-boot-multi-region-ha/elasticache-global.html)
[![GitHub Pages](https://img.shields.io/badge/GitHub_Pages-Live_Diagram-brightgreen?logo=github)](https://tuannx.github.io/spring-boot-multi-region-ha/elasticache-global.html)

This case demonstrates an ElastiCache Global Datastore–style deployment with
**Valkey 8** and a Spring Boot application in two regions:

```text
                       global router :8200
                    ┌──────────┴──────────┐
                    │                     │
             app-us :8280          app-eu :8281
              local Valkey           local Valkey
                    │                     │
              valkey-us (primary) ──replication──► valkey-eu (replica)
                    └──────── async, primary→replica ────────┘
```

It is intentionally separate from the root Aurora/PostgreSQL case and from the
Cassandra case. Aurora fences a single global writer for a relational store;
Cassandra accepts writes in both datacenters; a Global Datastore has **one
primary region for writes** and read-only replica regions, with explicit
promotion when the primary region fails.

## Guarantees demonstrated

- Two Valkey 8 instances: `valkey-us` (primary) and `valkey-eu` (replica via
  `replicaof`).
- Each Spring Boot app talks only to its local Valkey instance.
- US writes replicate asynchronously to the EU replica; the acceptance script
  measures and prints the visibility delay (the stale-read window).
- The EU replica rejects writes while it is a replica (Valkey `READONLY`);
  writes only succeed there after promotion.
- On a complete US outage the global router moves traffic to the EU app, the
  replica is promoted (`REPLICAOF NO ONE`), and EU accepts writes.
- The old primary rejoins as a replica of the promoted region and converges.

Asynchronous replication is the RPO contract: writes acknowledged on the
primary can be lost if it dies before they replicate. Promotion therefore
trades a bounded, *unmeasured-in-advance* data-loss window for write
availability. The measured visibility delay in the acceptance output is a
local lower bound, not a production SLO.

## Run

Requirements: Docker Compose v2, `curl`, and `jq`.

```bash
./scripts/elasticache-global-e2e.sh --start --cleanup
```

The acceptance flow:

1. Waits for both apps and both Valkey instances.
2. Writes through US and verifies the row appears in EU, printing the
   replication visibility delay in ms.
3. Stops the US app and the US (primary) Valkey.
4. Promotes the EU replica and verifies a write through the global router
   lands in EU.
5. Restarts the US Valkey as a replica of EU and verifies the EU write
   becomes visible in US.

Use `--keep` instead of `--cleanup` to inspect the environment after the run.

```bash
docker compose -p multiregion-elasticache \
  -f cases/elasticache-global/docker-compose.yml ps

curl -s http://localhost:8280/health | jq
curl -s http://localhost:8281/health | jq

curl -s -X POST http://localhost:8200/api/catalog \
  -H "X-Source-Region: us-east-1" \
  -H "Content-Type: application/json" \
  -d '{"name":"Regional catalog item","price":49.99}' | jq
```

Ports:

| Port | Service |
|------|---------|
| `8200` | Global nginx router |
| `8280` | US Spring Boot app |
| `8281` | EU Spring Boot app |
| `16379` | US Valkey (primary) |
| `16380` | EU Valkey (replica) |

## Floci control plane (Valkey via ElastiCache API)

Floci backs ElastiCache with a real Valkey container (default image
`valkey/valkey:8`, Redis/Valkey protocol with IAM auth and SigV4 validation).
With the Floci profile running (`docker-compose.floci.yml`), provision one
ElastiCache cluster per region through the AWS-compatible API:

```bash
cd cases/elasticache-global/terraform
terraform init
terraform apply \
  -var="floci_us_endpoint=http://localhost:4566" \
  -var="floci_eu_endpoint=http://localhost:4567"
```

This proves the control-plane path (cluster creation via the ElastiCache
API). The runnable primary/replica data plane used by the acceptance script
is the Compose stack above; a Floci-managed cluster is a single instance per
region and does not emulate Global Datastore cross-region replication,
promotion ordering, or failover DNS.

## Trade-off vs home-region reads (root Aurora case)

The root case pins reads to the home region and writes to one fenced writer,
so a reader can miss a just-committed write from the other region. This case
has the same shape at cache speed: replica reads are fast and local, but
stale by the replication window, and the replica is read-only until
promotion. Choose it for read-heavy, loss-tolerant data (catalogs, sessions,
derived views), not as the system of record.

## Source layout

```text
cases/elasticache-global/
  app/            Spring Boot app (catalog domain/application/port, Redis persistence)
  docker/         nginx global router (X-Source-Region, backup failover)
  terraform/      Floci ElastiCache provisioning (control-plane proof)
  docker-compose.yml
scripts/elasticache-global-e2e.sh   acceptance (gate for this case)
```

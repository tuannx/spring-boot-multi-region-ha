# Four home-region writers

The default `docker compose up` stack is still two regions and one writer. This file is the four-region topology from [ADR 0003](adr/0003-home-region-sharding.md).

Stop the default stack before starting this one. Both projects set `container_name` on `multiregion-us`, `multiregion-eu`, `multiregion-router`, and `multiregion-otel-collector`. The Compose project name `active-regions` keeps the volumes apart.

```bash
docker compose -p active-regions -f docker-compose.active-regions.yml up -d --build
```

| Region | Database | Application | Host database port | Host application port | Sequence start | Pair |
| --- | --- | --- | --- | --- | --- | --- |
| `us-east-1` | `postgres-us` | `app-us` | 5442 | 8080 | 1000000000 | `eu-west-1` |
| `eu-west-1` | `postgres-eu` | `app-eu` | 5433 | 8081 | 2000000000 | `us-east-1` |
| `ap-southeast-1` | `postgres-apse1` | `app-apse1` | 5434 | 8082 | 3000000000 | `ap-northeast-1` |
| `ap-northeast-1` | `postgres-apne1` | `app-apne1` | 5435 | 8083 | 4000000000 | `ap-southeast-1` |

The drill widens `products_id_seq` to `bigint` before `setval`. V1 still creates that sequence as `integer`, whose maximum is 2147483647, so the `ap-southeast-1` and `ap-northeast-1` bases do not fit until the drill alters the sequence. The `products.id` column is already `bigint`.

The router listens on host port 8000. Send `X-Source-Region` with one of the four region ids. The router tries that region, then its pair, then the remaining regions by priority. A request that lands on the pair is a new key in the pair's shard. The application stamps its own region. A body that names a different region receives HTTP 409 while `SHARD_OWNERSHIP_ENFORCED=true`.

Each application uses the AWS JDBC Wrapper with `WRAPPER_DIALECT=aurora-pg`, `CLUSTER_INSTANCE_PATTERN=?:5432`, and `READ_FAILOVER_MODE=strict-writer`. The read pool stays on the local writer, so a drill that kills the pair does not move reads onto the dead database. Queues are disabled. `eu-west-1` is a writer only because `SHARD_WRITER_MODE=true`. The two-region lab leaves that variable unset, so EU stays a reader there.

Publications are row-filtered: `shard_<code>` publishes `products` where `region` equals that database's region. Each database subscribes to the other three. Slots are named `shard_<owner>_to_<subscriber>`.

## Nightly scenarios

`FAILOVER_DRILL_PROFILE=nightly` runs the two-region scenarios and then:

| Scenario | Fault | Regions down | Synchronous standby |
| --- | --- | --- | --- |
| `lose-1-of-4` | `kill-regions` | `ap-southeast-1` | `us-east-1`→`eu-west-1`, `eu-west-1`→`us-east-1`, `ap-southeast-1`→`ap-northeast-1`, `ap-northeast-1`→`us-east-1` |
| `lose-2-of-4` | `kill-regions` | `eu-west-1`, `ap-northeast-1` | `us-east-1`→`ap-southeast-1`, `eu-west-1`→`us-east-1`, `ap-southeast-1`→`us-east-1`, `ap-northeast-1`→`ap-southeast-1` |
| `partition-2-2` | `split-pairs` | none | each region → its pair |

`partition-2-2` loads `docker-compose.active-regions-partition.yml`. Cross-pair subscriptions use Toxiproxy `2.12.0` on host ports 5601 (`us-east-1`), 5602 (`eu-west-1`), 5603 (`ap-southeast-1`), and 5604 (`ap-northeast-1`). Pair subscriptions stay on the database hostname. The API is `http://127.0.0.1:8474`.

These scenarios run three iterations, the same count as the single-writer drill. The workload is one writer thread per region against the router, with no read workers. Each application attaches the OpenTelemetry Java agent (`JAVA_OPTS` replaces the AOT cache) and exports to the Collector in this Compose file. The drill writes `reports/failover-drill/scenarios/<name>/otlp-trace.json` and fails the scenario when that file is missing or does not cover the fault. `write_conflicts` and `convergence_lag_seconds` are recorded next to RTO, RPO, lost writes, and p99. A missing value fails the active-region gate. An older baseline artifact may still omit those two fields. The conflict limit is 0. The convergence limit is 15 seconds. A probe that does not catch up is reported as 16 seconds. The nightly job timeout is 120 minutes. The passing worst case, including the existing scenarios, is about 90 minutes and is recorded in [ADR 0003](adr/0003-home-region-sharding.md).

The pull-request drill does not start this stack. [`.github/workflows/e2e.yml`](../.github/workflows/e2e.yml) runs `docker compose config` on the four-region files.

## Floci

```bash
docker compose -f docker-compose.floci.yml -f docker-compose.floci-active-regions.yml config
```

The overlay adds Floci for `ap-southeast-1` (`4568`) and `ap-northeast-1` (`4569`), plus socat proxies `postgres-apse1` and `postgres-apne1`. `scripts/floci-e2e.sh` does not load the overlay.

## Later steps

Fenced adoption of a lost shard is not in this topology. That step owns the sequence and promotes the dead region's keyspace onto the survivor. The default demo and the Floci acceptance script still use two regions. A real account would add one Aurora cluster per shard. Cross-region reads do not wait for the subscriber to apply.

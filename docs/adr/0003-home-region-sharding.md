# ADR 0003: Home-region sharding for four active regions

## Decision

Four regions run active-active by home-region sharding. Each product key has one owning region. That region's PostgreSQL commits the key locally. The other regions receive the row by logical replication. The AWS Advanced JDBC Wrapper stays the per-cluster writer selector. Shard choice sits above it: `X-Source-Region` selects the home application, and that application writes its local database.

The score below uses equal weights. Each criterion is an integer from 1 to 5. A higher number is better. The totals are 27, 25, 19, 15, and 13, so home-region sharding is the selected strategy.

| Strategy | RPO / consistency | Conflict handling | Commit latency | Local emulation | JDBC wrapper fit | Effort | Total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Home-region sharding with per-region writers | 4 | 5 | 5 | 5 | 5 | 3 | 27 |
| Aurora Global single-writer + write forwarding | 4 | 5 | 2 | 5 | 5 | 4 | 25 |
| CockroachDB / YugabyteDB | 5 | 4 | 3 | 3 | 2 | 2 | 19 |
| Aurora DSQL | 5 | 4 | 2 | 1 | 2 | 1 | 15 |
| DynamoDB Global Tables | 2 | 2 | 5 | 2 | 1 | 1 | 13 |

The rubric is fixed. RPO / consistency scores 5 when an acknowledgement is durable in every region before the client returns, and 1 when an acknowledgement can disappear or be overwritten with no bound. Conflict handling scores 5 when one region owns the key and a foreign writer rejects the write, and 1 when the database keeps a silent last-writer-wins value. Commit latency scores 5 when the home commit does not wait on another region, and 1 when every commit waits on a cross-region quorum. Local emulation scores 5 when the current PostgreSQL, Floci, and Testcontainers path can run the strategy with zero cloud spend, and 1 when this repository has no stand-in. JDBC wrapper fit scores 5 when the AWS Advanced JDBC Wrapper remains the failover selector inside one cluster, and 1 when the write path leaves JDBC. Effort scores 5 when this repository already is that design, and 1 when the change replaces the database and the write path.

Aurora Global write forwarding keeps one writer. Replicas are asynchronous, which is the same RPO shape as a home commit plus one synchronous peer, so both score 4 on RPO. Forwarding has no multi-writer conflict. A commit from a non-primary region waits on the primary, so commit latency scores 2. The current two-region lab already emulates that single writer, and the wrapper already targets it. It remains one writer, so it does not meet the four-region goal. Its total is 25.

Aurora DSQL is active-active and serializable across regions, with transaction aborts on conflict. Commits wait on a cross-region quorum. Floci does not emulate DSQL, and the wrapper's Aurora topology is a different engine. Adopting it replaces the local drill. Its total is 15.

DynamoDB Global Tables commit in the home region and replicate asynchronously. Conflicts resolve by last-writer-wins. The application would leave the JDBC wrapper and the `products` table. Floci can emulate DynamoDB, and this repository does not use that data path. Its total is 13.

CockroachDB and YugabyteDB can commit a serializable multi-region transaction and surface conflicts as retries. A global table still pays cross-region latency unless each table is pinned to a home region, which is sharding again. Both speak the PostgreSQL wire protocol. Neither is the Aurora topology the wrapper discovers, and neither is the Floci path this drill runs. Their total is 19.

Home-region sharding commits in the owning region. A second region can be a synchronous apply target for the drill's zero-loss scenarios. The database rejects a second owner by not accepting the key on that writer: the application returns HTTP 409 when `SHARD_OWNERSHIP_ENFORCED` is true and the row names another region. The wrapper still chooses the writer inside the home cluster. PostgreSQL 18 logical replication, the existing Floci control planes, and Testcontainers run it with zero cloud spend. The new topology, the replication mesh, and the drill scenarios are the effort. Its total is 27.

## Locked trade-offs

1. One owning region per key. There is no last-writer-wins merge.
2. The home commit is local ACID. Cross-region copies are asynchronous logical replication, except where a drill scenario sets `synchronous_commit=remote_apply` for one standby.
3. Losing a region can lose an acknowledged write that no survivor applied. The drill counts those writes. `lose-1-of-4`, `lose-2-of-4`, and `partition-2-2` target 0 lost writes by synchronous apply to a standby that the scenario leaves up.
4. Client traffic for a dead region fails over to its pair. The pair writes new keys into its own shard. The application stamps its own region. Adopting the dead shard's keyspace is a later step.
5. The JDBC wrapper remains the per-cluster writer selector. Shard choice is above it: `X-Source-Region` plus the home writer host.
6. The four-region Compose project is nightly-only. Pull-request end-to-end and the single-writer drill stay on two regions. Per-push CI runs `docker compose config` on the four-region files.
7. Aurora DSQL, DynamoDB Global Tables, and CockroachDB / YugabyteDB are not introduced. Write forwarding remains the existing two-region lab until a later step.
8. `write_conflicts` counts product names whose `(region, price)` differ across databases, plus HTTP 409 shard rejections. The limit is 0. The single-writer drill records 0.
9. `convergence_lag_seconds` on the single-writer drill equals `rpo_seconds`. On the four-region drill it is the seconds from a probe commit on each live home writer until every live subscriber has applied it. The three new scenarios use an upper bound of 15 seconds. A probe that is still short of that bound is reported as the bound plus one second, so a value equal to the bound still passes.
10. The active scenarios run one iteration, one writer thread per region, and no read workers. They do not attach the OpenTelemetry agent. The trace gate stays on the single-writer scenarios. Queues are disabled. `SHARD_OWNERSHIP_ENFORCED` is set only on the four-region stack.

## First increment

`docker-compose.active-regions.yml` starts `us-east-1`, `eu-west-1`, `ap-southeast-1`, and `ap-northeast-1`. Each application is a home writer. The ServiceTalk router serves each region and fails a dead region over to its pair: `us-east-1` with `eu-west-1`, and `ap-southeast-1` with `ap-northeast-1`. `docker-compose.floci-active-regions.yml` adds Floci control planes and socat proxies for the two new regions. It does not start application containers, because the Floci acceptance script still provisions two RDS instances.

The nightly profile adds `lose-1-of-4` (kill `ap-southeast-1`), `lose-2-of-4` (kill `eu-west-1` and `ap-northeast-1`), and `partition-2-2` (west `{us-east-1, eu-west-1}` against east `{ap-southeast-1, ap-northeast-1}`). Each publisher's synchronous standby is a region that the scenario does not kill. In `lose-1-of-4`, `ap-northeast-1` syncs to `us-east-1` because its pair is the killed region. Cross-pair subscriptions in `partition-2-2` dial Toxiproxy. Pair subscriptions stay on the direct hostname.

## Trade-offs

Write forwarding scored 25 and is the closest alternative. Choosing it would keep the current drill and would leave three regions unable to commit locally. The two-point gap is that gap: home commit latency and a real per-region writer, against a design this repository already runs. The two-region lab stays on write forwarding until a later change moves it.

Synchronous apply to one peer bounds lost writes for the new scenarios and adds that peer's apply time to the home commit. A commit stalls when that peer stops streaming. The sync target table is therefore not the router pair when the pair is the region the scenario kills. The other subscribers stay asynchronous. `convergence_lag_seconds` measures those subscribers after the fault is healed. `partition-2-2` re-enables the proxies before the probe, so the probe waits behind the partitioned backlog.

The pair does not take ownership of the dead region's existing keys. Those keys stay on the surviving replica that applied them. New writes for clients who named the dead region land in the pair's shard. A later step has to fence the old sequence and promote that keyspace. Doing it in this step would mix a routing drill with a shard move.

Four application processes are too heavy for the pull-request end-to-end job. The nightly workflow already has a two-hour budget and runs these scenarios once each. Per-push CI only parses the Compose files.

`write_conflicts` and `convergence_lag_seconds` are not regression metrics. A baseline artifact from before this ADR can omit them. The single-writer gate still records both, with conflicts at 0 and convergence equal to `rpo_seconds`.

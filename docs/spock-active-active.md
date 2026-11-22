# Spock active-active: two writers, no fencing

Every other stack in this repository has exactly one writer. This option is
the opposite: `postgres-us` and `postgres-eu` are both writers, and the
[Spock](https://docs.pgedge.com/spock-v5/) extension replicates changes in
both directions. There is no promotion, no fencing, and no writer to kill —
conflicts are expected and are resolved by rule instead of being prevented.

```bash
docker compose -f docker-compose.spock.yml up -d
./scripts/spock-setup.sh     # extensions, demo table, nodes, subscriptions
./scripts/spock-verify.sh    # writes one row per node, proves convergence
```

| Node | Service | Host port | Spock node | `snowflake.node` |
| --- | --- | --- | --- | --- |
| `us-east-1` | `postgres-us` | 5452 | `n1` | 1 |
| `eu-west-1` | `postgres-eu` | 5453 | `n2` | 2 |

The image is the official pgEdge Enterprise Postgres image
(`ghcr.io/pgedge/pgedge-postgres:18-spock5-minimal`), which ships Spock,
snowflake and lolor pre-installed; the `minimal` flavor is enough. Server
parameters follow the Spock install guide: `shared_preload_libraries=spock`,
`wal_level=logical`, `track_commit_timestamp=on` (the default conflict
resolver compares commit timestamps, so this is not optional).

## What the setup script does

1. Creates the `spock` and `snowflake` extensions and a demo table,
   identically, on both nodes. Spock DDL replication stays **off**: schema
   is applied per node, never streamed.
2. Registers node `n1` on `postgres-us` and `n2` on `postgres-eu`.
3. Adds only `spock_demo` to the `default` replication set.
4. Creates subscription `sub_eu_us` (EU subscribes to US), waits for sync,
   then `sub_us_eu` (US subscribes to EU).

```sql
CREATE TABLE public.spock_demo (
  id         BIGINT PRIMARY KEY DEFAULT snowflake.nextval(),
  payload    TEXT NOT NULL,
  region     VARCHAR(50) NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

The demo table is separate from `products` on purpose. `products.id` is a
JPA `IDENTITY` column fed by a local sequence; two writers minting ids from
two local sequences collide on the same primary keys within the first
concurrent inserts. Snowflake ids embed the node id (`snowflake.node` must
be unique per node — the extension does not enforce this, duplicates
silently collide), so both nodes can insert forever without meeting.

## The applications are not wired into this option

`docker-compose.spock.yml` contains the two databases and a `db-tools`
client only. The Spring Boot apps cannot run against these nodes with
environment changes alone, for three reasons rooted in the schema the apps
own:

1. **The fencing trigger.** Flyway V1 installs
   `products_require_writer_mode`, which rejects product writes on the node
   whose `writer_mode` is false, and the Aurora topology mock reports
   exactly one `MASTER_SESSION_ID`. Two writers are a contradiction in that
   model, not a configuration of it.
2. **Flyway runs on both nodes.** Each app migrates its home database. If
   `products` (or `flyway_schema_history`, or `failover_control`) were in a
   replication set, both sides would apply the same DDL and the same seed
   inserts — the seeds carry identical `SERIAL` ids — and replication would
   spend its life resolving self-inflicted conflicts.
3. **IDENTITY ids.** See above: local sequences on two writers guarantee
   primary-key collisions; the resolver then silently discards one side of
   each collision (next section).

Wiring the apps is a follow-up that needs an app-side profile: a Flyway
baseline without the fencing trigger for this stack, snowflake-backed id
generation in the entity, and the JDBC wrapper reduced to a local-writer
pool (the `aurora-pg` dialect and `failover2` plugin have no topology to
read here — there is no writer to fail over to). The per-app env shape
would be:

```text
app-us:  ACTIVE_WRITER_DB_HOST=postgres-us  LOCAL_DB_HOST=postgres-us
app-eu:  ACTIVE_WRITER_DB_HOST=postgres-eu  LOCAL_DB_HOST=postgres-eu
# FAILOVER_WRITER_DB_HOST is meaningless in this option: nothing fails over.
```

Each app writes to its home node — "write to the nearest writer" is the
only routing rule active-active has.

## Problems with this option

Read this section as the price list. Everything here is a property of
asynchronous multi-master replication itself, not of this Compose file.

### Eventual consistency is the consistency model

Replication is asynchronous. A row written in US is visible in EU when the
subscription applies it — normally sub-second on a LAN, unbounded under
load or a partition. There is no read-your-writes across regions: a client
that writes via US and immediately reads via EU can miss its own row. Any
flow that needs a cross-region read to see a cross-region write must wait,
route the read to the writing node, or not use this option.

### There are no distributed transactions

A transaction lives and commits on exactly one node. Nothing coordinates a
commit across US and EU — no two-phase commit, no global atomicity, no
cross-node snapshot. Atomicity that spans regions becomes application
work: idempotent consumers, outbox tables, and reconciliation jobs. If a
use case cannot be decomposed that way, it does not belong on this option.

### Conflict resolution can lose updates silently

When two nodes modify the same row inside the replication lag window, the
default resolver is `last_update_wins`: the version with the newest commit
timestamp is kept and the other is discarded. Replication stays green; the
losing write is simply gone. Spock records resolutions in
`spock.resolutions` (when `spock.save_resolutions` is enabled), but nothing
alerts on them by itself. The one structural escape is delta-apply columns
(`spock.delta_apply`), which merge additive numeric changes — counters and
balances — instead of overwriting; it does nothing for text, JSON, or
last-writer semantics.

### Some conflicts do not resolve at all

Two conflict classes are not resolvable: `update_missing` (an UPDATE
arrives for a row the local node does not have) and `update_exists` (an
UPDATE lands on a row that violates a unique constraint). These stop the
apply of that transaction with an ERROR and are recorded in
`spock.exception_log` for a human to fix. Production use of this option
therefore requires monitoring on both `spock.resolutions` and
`spock.exception_log`; an unwatched exception log is a replication outage
waiting to be noticed late.

### The schema lives under extra constraints

- Every replicated table needs a `PRIMARY KEY` or another valid replica
  identity; without one, UPDATEs and DELETEs cannot replicate.
- Effectively one unique index participates in conflict detection. Extra
  unique constraints can make an apply fail with `update_exists` instead of
  resolving.
- Deferrable unique constraints are silently skipped during INSERT
  conflict detection — the failure mode is duplicate rows, not an error.
- `UNLOGGED` and `TEMPORARY` tables are never replicated.
- Spock configuration requires superuser. In this stack that is the
  bootstrap `appuser`; a real deployment needs a dedicated admin path.

### Sequences must be snowflake

`bigserial` / `IDENTITY` columns are per-node counters and collide across
writers by construction. Any table that joins a replication set needs
cluster-unique ids: snowflake (used here), UUIDs, or an equivalent scheme.
Converting an existing sequence column is a schema project, not a flag.

### Schema changes can stop replication

Spock has no replication queue flush: there is no way to freeze writes,
drain every queued transaction, and then change the table. If a provider
and a subscriber change a table's shape while incompatible transactions
are still in flight, replication stops. Operationally: quiesce writes on
the affected tables, let subscriptions catch up, apply the change on every
node, then resume. This option keeps DDL replication off and applies the
demo schema per node for the same reason.

### Synchronous replication cancels the point

A subscription can be made synchronous (naming it in
`synchronous_standby_names`), after which every commit on the source waits
for the peer to apply. Across regions that is one inter-region round trip
added to every commit — the write latency this option exists to avoid,
paid to buy back a guarantee the rest of the model does not provide.

### When not to choose this option

Stay on the default single-writer stack when any of these are true:

- A flow needs strong consistency or atomicity across regions.
- A small set of hot rows is written from all regions at once (counters,
  inventory, shared balances without delta-apply).
- The schema leans on several unique constraints per table, deferrable
  constraints, or tables without primary keys.
- Nobody will own watching `spock.exception_log`.

Choose this option when writes are naturally partitioned by region
(follow-the-sun traffic, per-region ownership of rows), when occasional
last-update-wins loss on cross-written rows is acceptable or designed
away, and when the alternative — one global writer with inter-region
write latency for half the planet — is the worse trade.

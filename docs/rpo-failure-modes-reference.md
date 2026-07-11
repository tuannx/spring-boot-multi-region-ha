# RPO Failure Modes Reference

This reference maps Recovery Point Objective (RPO) risks to concrete detection queries, Spring Boot remediation patterns, configuration changes, and validation tests.

The current project is a local **warm standby / Aurora Global Database simulation**:

- One global writer at a time.
- Region-local read pools.
- AWS JDBC Wrapper `failover2` for disaster failover.
- PostgreSQL mock `pg_catalog.aurora_replica_status()` functions.
- Spring Retry around write operations to cover the failover window.

Active-active cases are included because they are common RPO traps, but they require different infrastructure: multi-writer data stores, quorum/lease services, conflict-free data types, or application-level merge protocols.

## How To Read This Document

Each case uses the same structure:

1. **Failure mode**: what can lose or corrupt acknowledged data.
2. **Detection SQL**: queries that reveal the condition.
3. **Java remediation**: Spring Boot code pattern to prevent or contain the risk.
4. **Config**: database or application settings.
5. **Validation**: how to prove the mitigation works.

RPO terms used here:

| Term | Meaning |
|------|---------|
| RPO = 0 | No committed acknowledged write can be lost. Requires synchronous replication or quorum acknowledgement. |
| RPO seconds | A bounded amount of recently committed data can be lost. Usually async replication with lag budgets. |
| RPO unbounded | The system can keep accepting writes without a known replication boundary. Data loss can exceed the operational target. |

## Summary Matrix

| # | Mode | Failure Mode | RPO Risk | Current Project Fit |
|---|------|--------------|----------|---------------------|
| 1 | Warm standby | Async replication lag | Seconds to minutes of acknowledged writes can be missing after failover | Add health gate and lag budget |
| 2 | Warm standby | Failover rollback | Clients see success for writes later absent in promoted region | Add idempotency and reconciliation |
| 3 | Warm standby | Replication slot bloat | Standby falls far behind or primary disk fills | Add WAL slot monitor and retention cap |
| 4 | Warm standby | Long-running transaction | Old snapshot blocks replay and increases lag | Add transaction timeout guard |
| 5 | Warm standby | Vacuum conflict | Standby cancels reads or delays cleanup, affecting freshness | Tune standby feedback and query budgets |
| 6 | Active-active | Last-write-wins conflict | Later timestamp overwrites valid business update | Use versions, merges, or CRDTs |
| 7 | Active-active | Causal consistency gap | User reads stale data after own write | Use read-your-writes tokens and region affinity |
| 8 | Active-active | Clock drift | Wrong winner under timestamp ordering | Use HLC and clock drift alarms |
| 9 | Active-active | Quorum loss | Minority partition accepts writes that cannot meet RPO | Reject writes without quorum |
| 10 | Active-active | Split-brain | Two leaders accept conflicting writes | Use leases and fencing tokens |
| 11 | Cross-cutting | Hazardous retry | Retry duplicates a write after timeout | Add idempotency key store |
| 12 | Cross-cutting | Schema migration | Old/new regions write incompatible data | Use expand-migrate-contract |
| 13 | Cross-cutting | Thundering herd | Reconnect storm extends outage and loses write budget | Add jitter and staged pool warmup |

---

# Part 1: Warm Standby

These cases match the architecture demonstrated by this project. The code snippets are designed to fit the existing Spring Boot package layout under `com.multiregion`.

## Case 1: Async Replication Lag

### Failure Mode

Warm standby usually replicates asynchronously. A write can commit on the current writer before the standby receives or replays it. If the writer dies during that gap, the promoted region has an RPO greater than zero.

### RPO Impact

- **RPO target**: bounded seconds.
- **Failure**: failover promotes a standby whose replay timestamp is older than the last acknowledged write.
- **Symptom**: recently created products, orders, or idempotency records are missing after failover.

### Detection SQL

On the primary:

```sql
SELECT
  application_name,
  client_addr,
  state,
  sync_state,
  pg_wal_lsn_diff(pg_current_wal_lsn(), replay_lsn) AS replay_lag_bytes,
  write_lag,
  flush_lag,
  replay_lag
FROM pg_stat_replication
ORDER BY replay_lag_bytes DESC;
```

On a standby:

```sql
SELECT
  now() - pg_last_xact_replay_timestamp() AS replay_delay,
  pg_is_in_recovery() AS is_standby;
```

For this local project, the mock topology exposes `REPLICA_LAG_IN_MSEC`:

```sql
SELECT
  SERVER_ID,
  SESSION_ID,
  COALESCE(REPLICA_LAG_IN_MSEC, 0) AS lag_ms,
  LAST_UPDATE_TIMESTAMP
FROM pg_catalog.aurora_replica_status()
ORDER BY lag_ms DESC;
```

### Java Remediation: ReplicationLagHealthIndicator.java

```java
package com.multiregion.health;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
public class ReplicationLagHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final long maxLagMs;

    public ReplicationLagHealthIndicator(
            @Qualifier("writeDataSource") DataSource dataSource,
            @Value("${rpo.max-replication-lag-ms:5000}") long maxLagMs) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.maxLagMs = maxLagMs;
    }

    @Override
    public Health health() {
        Long lagMs = jdbcTemplate.queryForObject("""
            SELECT COALESCE(MAX(REPLICA_LAG_IN_MSEC), 0)
            FROM pg_catalog.aurora_replica_status()
            """, Long.class);

        if (lagMs != null && lagMs > maxLagMs) {
            return Health.down()
                    .withDetail("rpo", "replication_lag_exceeded")
                    .withDetail("lagMs", lagMs)
                    .withDetail("maxLagMs", maxLagMs)
                    .build();
        }

        return Health.up()
                .withDetail("rpo", "within_lag_budget")
                .withDetail("lagMs", lagMs)
                .withDetail("maxLagMs", maxLagMs)
                .build();
    }
}
```

Use this signal in two places:

- Health checks should stop sending new write traffic to a region when lag exceeds the RPO budget.
- Failover automation should refuse promotion if the candidate is too far behind, unless the operator explicitly accepts data loss.

### Config

For RPO = 0 on specific critical writes, use synchronous commit in the transaction:

```sql
BEGIN;
SET LOCAL synchronous_commit = 'remote_apply';
INSERT INTO orders (...);
COMMIT;
```

Application config:

```yaml
rpo:
  max-replication-lag-ms: 5000
```

PostgreSQL primary config for synchronous standby:

```conf
synchronous_standby_names = 'FIRST 1 (standby_eu)'
synchronous_commit = remote_apply
```

Trade-off: stronger RPO increases write latency and reduces availability when the synchronous standby is unreachable.

### Validation

1. Inject artificial lag in the mock `aurora_replica_status()` function.
2. Call `/actuator/health`.
3. Verify the RPO health component flips to `DOWN`.
4. Attempt failover while lag exceeds budget.
5. Verify automation blocks promotion or emits an explicit data-loss acknowledgement requirement.

---

## Case 2: Failover Rollback

### Failure Mode

The client receives success from the old writer, then the old writer dies before the write reaches the promoted region. After failover, the client retries or reads the object and finds it missing.

### RPO Impact

- **RPO target**: zero for externally acknowledged business commands.
- **Failure**: acknowledged command is absent after failover.
- **Symptom**: duplicate orders, missing product creation, inconsistent payment state.

### Detection SQL

Detect gaps by comparing an application write ledger between regions:

```sql
SELECT request_id, aggregate_id, status, created_at
FROM write_ledger
WHERE created_at > now() - interval '15 minutes'
ORDER BY created_at DESC;
```

Find records acknowledged by the API but absent in the domain table:

```sql
SELECT l.request_id, l.aggregate_id, l.created_at
FROM write_ledger l
LEFT JOIN orders o ON o.id = l.aggregate_id
WHERE l.operation = 'CREATE_ORDER'
  AND l.status = 'ACKNOWLEDGED'
  AND o.id IS NULL
ORDER BY l.created_at DESC;
```

### Java Remediation: Order.java With Idempotency Key

```java
package com.multiregion.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private Instant createdAt;

    @Version
    private long version;
}
```

### Java Remediation: TransactionReconciliationService.java

```java
package com.multiregion.reconciliation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class TransactionReconciliationService {

    private final JdbcTemplate jdbcTemplate;

    public TransactionReconciliationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelayString = "${rpo.reconciliation-interval-ms:30000}")
    @Transactional
    public void reconcileAcknowledgedWrites() {
        List<String> missingRequestIds = jdbcTemplate.queryForList("""
            SELECT l.request_id
            FROM write_ledger l
            LEFT JOIN orders o ON o.id = l.aggregate_id
            WHERE l.operation = 'CREATE_ORDER'
              AND l.status = 'ACKNOWLEDGED'
              AND o.id IS NULL
              AND l.created_at > now() - interval '1 hour'
            """, String.class);

        for (String requestId : missingRequestIds) {
            jdbcTemplate.update("""
                UPDATE write_ledger
                SET status = 'NEEDS_REPLAY', updated_at = now()
                WHERE request_id = ?
                """, requestId);
        }
    }
}
```

### Config

```yaml
rpo:
  reconciliation-interval-ms: 30000
  write-ledger-retention-days: 7
```

Database constraints:

```sql
CREATE TABLE IF NOT EXISTS write_ledger (
  request_id varchar(128) PRIMARY KEY,
  aggregate_id uuid NOT NULL,
  operation varchar(64) NOT NULL,
  status varchar(32) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_orders_idempotency_key
ON orders (idempotency_key);
```

### Validation

1. Send a write with an `Idempotency-Key` header.
2. Force writer failure before simulated replication catches up.
3. Promote the standby.
4. Replay the same request.
5. Verify the system returns the original logical outcome or marks the write for reconciliation instead of creating duplicates.

---

## Case 3: Replication Slot Bloat

### Failure Mode

Physical or logical replication slots retain WAL until a standby or consumer catches up. If a standby is down too long, WAL grows. The primary can run out of disk, or the standby can become unrecoverable without a fresh base backup.

### RPO Impact

- **RPO target**: bounded seconds/minutes.
- **Failure**: standby is no longer a viable promotion target.
- **Symptom**: failover candidate is stale or primary disk pressure causes outage.

### Detection SQL

```sql
SELECT
  slot_name,
  slot_type,
  active,
  restart_lsn,
  confirmed_flush_lsn,
  pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS retained_wal,
  pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn) AS retained_wal_bytes
FROM pg_replication_slots
ORDER BY retained_wal_bytes DESC;
```

Disk pressure:

```sql
SELECT
  pg_size_pretty(SUM(size)) AS wal_size
FROM pg_ls_waldir();
```

### Java Remediation: WalSlotHealthIndicator.java

```java
package com.multiregion.health;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
public class WalSlotHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final long maxRetainedBytes;

    public WalSlotHealthIndicator(
            @Qualifier("writeDataSource") DataSource dataSource,
            @Value("${rpo.max-retained-wal-bytes:1073741824}") long maxRetainedBytes) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.maxRetainedBytes = maxRetainedBytes;
    }

    @Override
    public Health health() {
        Long retainedBytes = jdbcTemplate.queryForObject("""
            SELECT COALESCE(MAX(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)), 0)::bigint
            FROM pg_replication_slots
            WHERE restart_lsn IS NOT NULL
            """, Long.class);

        if (retainedBytes != null && retainedBytes > maxRetainedBytes) {
            return Health.down()
                    .withDetail("rpo", "wal_slot_bloat")
                    .withDetail("retainedBytes", retainedBytes)
                    .withDetail("maxRetainedBytes", maxRetainedBytes)
                    .build();
        }

        return Health.up()
                .withDetail("rpo", "wal_retention_within_budget")
                .withDetail("retainedBytes", retainedBytes)
                .build();
    }
}
```

### Config

PostgreSQL:

```conf
max_slot_wal_keep_size = '4GB'
wal_keep_size = '512MB'
archive_mode = on
archive_timeout = '60s'
```

Application:

```yaml
rpo:
  max-retained-wal-bytes: 1073741824
```

### Validation

1. Stop the standby or replication consumer.
2. Generate write traffic.
3. Verify retained WAL grows in `pg_replication_slots`.
4. Confirm the health indicator turns `DOWN` before disk pressure becomes critical.
5. Restart standby and verify retained WAL shrinks.

---

## Case 4: Long-Running Transaction

### Failure Mode

Long transactions hold old snapshots. On the primary, they delay cleanup. On a standby, long reads can conflict with replay. Both cases increase lag and make RPO worse.

### RPO Impact

- **RPO target**: bounded seconds.
- **Failure**: replay stalls behind an old transaction.
- **Symptom**: standby lag grows while traffic appears healthy.

### Detection SQL

```sql
SELECT
  pid,
  usename,
  application_name,
  state,
  now() - xact_start AS xact_age,
  wait_event_type,
  wait_event,
  left(query, 160) AS query
FROM pg_stat_activity
WHERE xact_start IS NOT NULL
ORDER BY xact_age DESC;
```

Detect old snapshots:

```sql
SELECT
  pid,
  backend_xmin,
  now() - xact_start AS age,
  left(query, 160) AS query
FROM pg_stat_activity
WHERE backend_xmin IS NOT NULL
ORDER BY age DESC;
```

### Java Remediation: TransactionTimeoutAspect.java

```java
package com.multiregion.guard;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Aspect
@Component
public class TransactionTimeoutAspect {

    private static final Logger log = LoggerFactory.getLogger(TransactionTimeoutAspect.class);
    private static final long WARN_AFTER_MS = 2000;

    @Around("@annotation(transactional)")
    public Object measureTransaction(ProceedingJoinPoint pjp, Transactional transactional) throws Throwable {
        long started = System.nanoTime();
        try {
            return pjp.proceed();
        } finally {
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            if (elapsedMs > WARN_AFTER_MS) {
                log.warn("Slow transaction: method={} elapsedMs={} readOnly={} timeout={}",
                        pjp.getSignature(), elapsedMs, transactional.readOnly(), transactional.timeout());
            }
        }
    }
}
```

Prefer explicit transaction timeouts on service methods:

```java
@Transactional(timeout = 5)
public Product save(Product product) {
    // write operation
}
```

### Config

PostgreSQL:

```conf
idle_in_transaction_session_timeout = '30s'
statement_timeout = '15s'
lock_timeout = '3s'
```

Spring:

```yaml
spring:
  transaction:
    default-timeout: 10
```

### Validation

1. Start a transaction and leave it idle.
2. Generate writes on the primary.
3. Verify the transaction appears at the top of `pg_stat_activity`.
4. Verify timeout kills or rolls back the transaction.
5. Verify replication lag stops growing.

---

## Case 5: Vacuum Conflict

### Failure Mode

Hot standby queries can conflict with WAL replay when the primary vacuums rows still visible to standby snapshots. PostgreSQL either cancels standby queries to keep replay moving or delays cleanup to protect standby reads.

### RPO Impact

- **RPO target**: bounded seconds.
- **Failure**: replay delay grows because standby reads are protected too aggressively.
- **Symptom**: either stale reads or cancelled analytical queries.

### Detection SQL

Standby conflicts:

```sql
SELECT
  datname,
  confl_tablespace,
  confl_lock,
  confl_snapshot,
  confl_bufferpin,
  confl_deadlock
FROM pg_stat_database_conflicts
ORDER BY datname;
```

Vacuum pressure:

```sql
SELECT
  relname,
  n_dead_tup,
  last_vacuum,
  last_autovacuum,
  vacuum_count,
  autovacuum_count
FROM pg_stat_user_tables
ORDER BY n_dead_tup DESC
LIMIT 20;
```

### Java Remediation

Use query budgets for replica reads so standby replay is not blocked by unlimited user queries:

```java
@Transactional(readOnly = true, timeout = 3)
public List<Product> findAll() {
    RoutingDataSource.routeTo("reader");
    try {
        return productRepository.findAll();
    } finally {
        RoutingDataSource.clearRoute();
    }
}
```

For reporting queries, route to a dedicated analytics replica instead of the HA promotion candidate.

### Config

PostgreSQL standby:

```conf
hot_standby_feedback = on
max_standby_streaming_delay = '10s'
max_standby_archive_delay = '30s'
```

If table bloat matters more than cancelled standby reads, keep `hot_standby_feedback = off` and set strict read timeouts.

`vacuum_defer_cleanup_age` exists but should be treated as a specialized tool; prefer measured query timeouts and proper replica roles first.

### Validation

1. Run a long read query on the standby.
2. Update/delete many rows on the primary.
3. Trigger vacuum.
4. Verify either replay remains within budget or the long read is cancelled within the configured delay.
5. Confirm RPO health still reports acceptable lag.

---

# Part 2: Active-Active

These cases require infrastructure beyond this repo's current warm standby simulation. They are included so the project can explain why active-active is not just "two writers" and why RPO remediation moves into data modeling and consistency protocols.

## Case 6: LWW Conflict

### Failure Mode

Last-write-wins (LWW) chooses the value with the latest timestamp. It can silently discard a valid update from another region.

### RPO Impact

- **RPO target**: no lost business intent.
- **Failure**: data exists but user intent is overwritten.
- **Symptom**: inventory, profile, or order state reverts without an error.

### Detection SQL

Find conflicting updates to the same aggregate:

```sql
SELECT
  aggregate_id,
  COUNT(DISTINCT region) AS regions,
  MIN(updated_at) AS first_update,
  MAX(updated_at) AS last_update
FROM product_update_events
WHERE updated_at > now() - interval '10 minutes'
GROUP BY aggregate_id
HAVING COUNT(DISTINCT region) > 1
ORDER BY last_update DESC;
```

### Java Remediation: ProductWithVersion.java

```java
package com.multiregion.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
public class ProductWithVersion {

    @Id
    private UUID id;

    @Version
    private long version;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private String lastWriterRegion;

    public ProductWithVersion merge(ProductWithVersion incoming) {
        ProductWithVersion merged = new ProductWithVersion();
        merged.id = id;
        merged.name = incoming.name != null ? incoming.name : name;
        merged.price = incoming.price != null ? incoming.price : price;
        merged.lastWriterRegion = incoming.lastWriterRegion;
        return merged;
    }
}
```

### Java Remediation: PNCounter.java

For quantities, do not use LWW. Use a CRDT counter:

```java
package com.multiregion.crdt;

import java.util.HashMap;
import java.util.Map;

public class PNCounter {

    private final Map<String, Long> increments = new HashMap<>();
    private final Map<String, Long> decrements = new HashMap<>();

    public void increment(String region, long amount) {
        increments.merge(region, amount, Long::sum);
    }

    public void decrement(String region, long amount) {
        decrements.merge(region, amount, Long::sum);
    }

    public long value() {
        long inc = increments.values().stream().mapToLong(Long::longValue).sum();
        long dec = decrements.values().stream().mapToLong(Long::longValue).sum();
        return inc - dec;
    }

    public void merge(PNCounter other) {
        other.increments.forEach((region, value) ->
                increments.merge(region, value, Math::max));
        other.decrements.forEach((region, value) ->
                decrements.merge(region, value, Math::max));
    }
}
```

### Config

```yaml
consistency:
  conflict-resolution: merge-required
  reject-lww-for:
    - orders
    - payments
    - inventory
```

### Validation

1. Write different values for the same aggregate in two regions.
2. Replicate events in both orders.
3. Verify the final state is deterministic.
4. Verify no user intent is silently dropped; unresolved conflicts must be visible.

---

## Case 7: Causal Consistency

### Failure Mode

A user writes in region A, then reads in region B before the write replicates. The system violates read-your-writes even though each region is locally healthy.

### RPO Impact

- **RPO target**: no user-visible loss of acknowledged writes.
- **Failure**: write is not lost permanently, but the user observes it as missing.
- **Symptom**: "I submitted it, then the next page said it does not exist."

### Detection SQL

Track session reads before their causal write watermark:

```sql
SELECT
  session_id,
  aggregate_id,
  required_lsn,
  observed_lsn,
  region,
  created_at
FROM causal_read_violations
WHERE created_at > now() - interval '1 hour'
ORDER BY created_at DESC;
```

### Java Remediation: ConsistentReadService.java

```java
package com.multiregion.consistency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class ConsistentReadService {

    private final JdbcTemplate jdbcTemplate;

    public ConsistentReadService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void waitUntilReplayCatchesUp(String requiredLsn, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Boolean caughtUp = jdbcTemplate.queryForObject("""
                SELECT pg_last_wal_replay_lsn() >= ?::pg_lsn
                """, Boolean.class, requiredLsn);
            if (Boolean.TRUE.equals(caughtUp)) {
                return;
            }
            sleep(50);
        }
        throw new IllegalStateException("Replica did not catch up to required LSN: " + requiredLsn);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
```

### Java Remediation: RegionAffinityInterceptor.java

```java
package com.multiregion.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.HandlerInterceptor;

public class RegionAffinityInterceptor implements HandlerInterceptor {

    private final String region;

    public RegionAffinityInterceptor(@Value("${AWS_REGION:us-east-1}") String region) {
        this.region = region;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.addCookie(new Cookie("last_write_region", region));
        return true;
    }
}
```

### Config

```yaml
consistency:
  read-your-writes:
    enabled: true
    wait-timeout-ms: 1500
    fallback-to-writer: true
```

### Validation

1. Write in region A and capture the commit LSN or logical version.
2. Immediately read in region B.
3. Verify the read waits, routes to the writer, or returns a clear `409 Not Yet Replicated` response.

---

## Case 8: Clock Drift

### Failure Mode

Timestamp-based conflict resolution assumes clocks are accurate. If a region clock drifts forward, its writes can win conflicts incorrectly.

### RPO Impact

- **RPO target**: no lost business update due to infrastructure clock skew.
- **Failure**: causally older data overwrites newer data.
- **Symptom**: impossible ordering in audit trail.

### Detection SQL

Compare database time to an external or leader-provided time source:

```sql
SELECT
  now() AS db_time,
  clock_timestamp() AS wall_time,
  extract(epoch FROM clock_timestamp() - now()) * 1000 AS transaction_clock_delta_ms;
```

Detect impossible event ordering:

```sql
SELECT aggregate_id, event_id, region, occurred_at, previous_event_at
FROM domain_events
WHERE occurred_at < previous_event_at
ORDER BY occurred_at DESC;
```

### Java Remediation: HybridLogicalClock.java

```java
package com.multiregion.time;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

public class HybridLogicalClock {

    private final AtomicLong lastPhysicalMillis = new AtomicLong();
    private final AtomicLong logicalCounter = new AtomicLong();

    public synchronized Timestamp tick() {
        long now = Instant.now().toEpochMilli();
        long last = lastPhysicalMillis.get();
        if (now > last) {
            lastPhysicalMillis.set(now);
            logicalCounter.set(0);
        } else {
            logicalCounter.incrementAndGet();
        }
        return new Timestamp(lastPhysicalMillis.get(), logicalCounter.get());
    }

    public synchronized Timestamp merge(Timestamp remote) {
        long now = Instant.now().toEpochMilli();
        long maxPhysical = Math.max(now, Math.max(lastPhysicalMillis.get(), remote.physicalMillis()));
        long nextLogical = maxPhysical == remote.physicalMillis()
                ? remote.logicalCounter() + 1
                : logicalCounter.incrementAndGet();
        lastPhysicalMillis.set(maxPhysical);
        logicalCounter.set(nextLogical);
        return new Timestamp(maxPhysical, nextLogical);
    }

    public record Timestamp(long physicalMillis, long logicalCounter) {
    }
}
```

### Java Remediation: ClockDriftMonitor.java

```java
package com.multiregion.time;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ClockDriftMonitor {

    private final long maxDriftMs;

    public ClockDriftMonitor(@Value("${consistency.max-clock-drift-ms:100}") long maxDriftMs) {
        this.maxDriftMs = maxDriftMs;
    }

    @Scheduled(fixedDelayString = "${consistency.clock-check-ms:10000}")
    public void checkClock() {
        long observedDriftMs = queryTimeAuthorityDrift();
        if (Math.abs(observedDriftMs) > maxDriftMs) {
            throw new IllegalStateException("Clock drift exceeds consistency budget: " + observedDriftMs);
        }
    }

    private long queryTimeAuthorityDrift() {
        return 0;
    }
}
```

### Config

```yaml
consistency:
  max-clock-drift-ms: 100
  conflict-clock: hybrid-logical-clock
```

Infrastructure:

```conf
chrony / NTP enabled
alert if offset > 50ms
reject timestamp-only LWW for critical aggregates
```

### Validation

1. Simulate one region clock ahead by 2 seconds.
2. Write conflicting updates in both regions.
3. Verify HLC order is deterministic and conflict policy does not blindly trust wall time.

---

## Case 9: Quorum Loss

### Failure Mode

An active-active system that requires quorum can lose enough replicas that it cannot guarantee the configured RPO. If the application keeps accepting writes in a minority partition, those writes may be rolled back or rejected later.

### RPO Impact

- **RPO target**: quorum-acknowledged writes only.
- **Failure**: non-quorum writes are accepted.
- **Symptom**: committed-looking writes disappear after partition healing.

### Detection SQL

Track region acknowledgements:

```sql
SELECT
  request_id,
  COUNT(DISTINCT region) AS ack_regions,
  MIN(ack_at) AS first_ack,
  MAX(ack_at) AS last_ack
FROM write_acknowledgements
WHERE created_at > now() - interval '10 minutes'
GROUP BY request_id
HAVING COUNT(DISTINCT region) < 2
ORDER BY last_ack DESC;
```

### Java Remediation: TopologyAwareConsistency.java

```java
package com.multiregion.consistency;

import java.util.Set;

public class TopologyAwareConsistency {

    private final int requiredAcks;

    public TopologyAwareConsistency(int requiredAcks) {
        this.requiredAcks = requiredAcks;
    }

    public boolean canAcceptWrite(Set<String> reachableRegions) {
        return reachableRegions.size() >= requiredAcks;
    }

    public void requireWriteQuorum(Set<String> reachableRegions) {
        if (!canAcceptWrite(reachableRegions)) {
            throw new QuorumUnavailableException(
                    "Rejecting write because reachableRegions=" + reachableRegions.size()
                            + " requiredAcks=" + requiredAcks);
        }
    }
}
```

### Java Remediation: CrossRegionWriteService.java

```java
package com.multiregion.consistency;

import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class CrossRegionWriteService {

    private final TopologyAwareConsistency consistency;
    private final RegionReachability regionReachability;

    public CrossRegionWriteService(
            TopologyAwareConsistency consistency,
            RegionReachability regionReachability) {
        this.consistency = consistency;
        this.regionReachability = regionReachability;
    }

    public void write(Object command) {
        Set<String> reachableRegions = regionReachability.currentReachableRegions();
        consistency.requireWriteQuorum(reachableRegions);
        // persist command only after quorum precondition is satisfied
    }
}
```

### Config

```yaml
consistency:
  write-quorum:
    required-acks: 2
    reject-on-quorum-loss: true
```

### Validation

1. Partition one region away from the quorum.
2. Attempt writes in the minority partition.
3. Verify writes fail fast with a clear `503 Quorum Unavailable`.
4. Restore network and verify no rollback is needed for rejected writes.

---

## Case 10: Split-Brain

### Failure Mode

Two regions both believe they are leader and accept writes. Reconciliation later must choose between conflicting histories.

### RPO Impact

- **RPO target**: no divergent committed history.
- **Failure**: two leaders issue acknowledged writes for the same aggregate.
- **Symptom**: duplicate order numbers, conflicting inventory deductions, inconsistent ledgers.

### Detection SQL

```sql
SELECT
  aggregate_id,
  COUNT(DISTINCT leader_epoch) AS epochs,
  COUNT(DISTINCT leader_region) AS leader_regions,
  MIN(created_at) AS first_write,
  MAX(created_at) AS last_write
FROM write_ledger
WHERE created_at > now() - interval '30 minutes'
GROUP BY aggregate_id
HAVING COUNT(DISTINCT leader_region) > 1
ORDER BY last_write DESC;
```

### Java Remediation: LeaseBasedLeaderElection.java

```java
package com.multiregion.leader;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class LeaseBasedLeaderElection {

    private final JdbcTemplate jdbcTemplate;

    public LeaseBasedLeaderElection(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Long> tryAcquireLease(String region, Duration ttl) {
        return jdbcTemplate.query("""
            INSERT INTO leader_lease (name, region, fencing_token, expires_at)
            VALUES ('global-writer', ?, nextval('fencing_token_seq'), now() + (? || ' milliseconds')::interval)
            ON CONFLICT (name) DO UPDATE
            SET region = EXCLUDED.region,
                fencing_token = nextval('fencing_token_seq'),
                expires_at = EXCLUDED.expires_at
            WHERE leader_lease.expires_at < now()
            RETURNING fencing_token
            """,
            ps -> {
                ps.setString(1, region);
                ps.setLong(2, ttl.toMillis());
            },
            rs -> rs.next() ? Optional.of(rs.getLong("fencing_token")) : Optional.empty()
        );
    }
}
```

### Java Remediation: FencingTokenFilter.java

```java
package com.multiregion.leader;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class FencingTokenFilter extends OncePerRequestFilter {

    private final LeaderTokenValidator validator;

    public FencingTokenFilter(LeaderTokenValidator validator) {
        this.validator = validator;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (isWrite(request) && !validator.currentTokenIsValid()) {
            response.sendError(409, "Stale leader fencing token");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isWrite(HttpServletRequest request) {
        return !"GET".equalsIgnoreCase(request.getMethod());
    }
}
```

### Config

```yaml
leader-election:
  lease-ttl-ms: 10000
  renew-before-ms: 5000
  require-fencing-token: true
```

Database:

```sql
CREATE SEQUENCE IF NOT EXISTS fencing_token_seq;

CREATE TABLE IF NOT EXISTS leader_lease (
  name varchar(64) PRIMARY KEY,
  region varchar(64) NOT NULL,
  fencing_token bigint NOT NULL,
  expires_at timestamptz NOT NULL
);
```

### Validation

1. Start two application regions.
2. Force both to believe they are leader.
3. Verify stale leader writes are rejected by fencing token validation.
4. Confirm only one increasing token sequence is accepted by downstream stores.

---

# Part 3: Cross-Cutting

These cases affect warm standby and active-active designs.

## Case 11: Hazardous Retry

### Failure Mode

The client times out after sending a write. The server may still commit. If the client retries without an idempotency key, the same logical action can happen twice.

### RPO Impact

- **RPO target**: no duplicate side effects.
- **Failure**: retry turns an uncertain write into duplicate committed state.
- **Symptom**: duplicate orders, double charges, duplicate inventory movement.

### Detection SQL

```sql
SELECT
  idempotency_key,
  COUNT(*) AS attempts,
  MIN(created_at) AS first_seen,
  MAX(created_at) AS last_seen
FROM idempotency_records
WHERE created_at > now() - interval '1 day'
GROUP BY idempotency_key
HAVING COUNT(*) > 1
ORDER BY attempts DESC;
```

Find duplicate business payloads without keys:

```sql
SELECT customer_id, amount, date_trunc('minute', created_at) AS minute_bucket, COUNT(*)
FROM orders
WHERE created_at > now() - interval '1 day'
GROUP BY customer_id, amount, date_trunc('minute', created_at)
HAVING COUNT(*) > 1;
```

### Java Remediation: IdempotencyFilter.java

```java
package com.multiregion.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class IdempotencyFilter extends OncePerRequestFilter {

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;

    public IdempotencyFilter(StringRedisTemplate redisTemplate, Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (isSafeMethod(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = request.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) {
            response.sendError(400, "Missing Idempotency-Key");
            return;
        }

        String redisKey = "idempotency:" + request.getMethod() + ":" + request.getRequestURI() + ":" + key;
        String cached = redisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            response.setStatus(200);
            response.getWriter().write(cached);
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, wrapped);

        if (wrapped.getStatus() >= 200 && wrapped.getStatus() < 300) {
            String body = new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8);
            redisTemplate.opsForValue().set(redisKey, body, ttl);
        }
        wrapped.copyBodyToResponse();
    }

    private boolean isSafeMethod(HttpServletRequest request) {
        return "GET".equalsIgnoreCase(request.getMethod())
                || "HEAD".equalsIgnoreCase(request.getMethod())
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }
}
```

### Config

```yaml
idempotency:
  enabled: true
  ttl: 24h
  required-methods:
    - POST
    - PUT
    - PATCH
    - DELETE
```

Redis cache policy:

```conf
maxmemory-policy noeviction
appendonly yes
```

For RPO-sensitive commands, the idempotency record should live in the same durability tier as the business write. Redis-only idempotency is a performance cache unless Redis persistence and replication are configured to the same RPO target.

### Validation

1. Send a POST with an idempotency key.
2. Simulate client timeout before receiving the response.
3. Retry with the same key.
4. Verify one domain record exists and the same response is returned.
5. Retry with the same key but different payload and verify the request is rejected.

---

## Case 12: Schema Migration

### Failure Mode

During a multi-region deployment, one region runs old code while another runs new code. If the database schema changes incompatibly, either region can write data the other cannot read.

### RPO Impact

- **RPO target**: no data loss during deploy/failover.
- **Failure**: rollback or failover loses fields or rejects rows.
- **Symptom**: deserialization failures, null columns, incompatible enum values.

### Detection SQL

Find columns present in one region but not another by exporting schema metadata:

```sql
SELECT table_name, column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public'
ORDER BY table_name, ordinal_position;
```

Detect writes using a new column before all regions are compatible:

```sql
SELECT id, created_at
FROM orders
WHERE new_required_column IS NOT NULL
ORDER BY created_at DESC
LIMIT 20;
```

### Java Remediation: SafeMigrationService.java

```java
package com.multiregion.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SafeMigrationService {

    private final JdbcTemplate jdbcTemplate;

    public SafeMigrationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean columnExists(String table, String column) {
        Boolean exists = jdbcTemplate.queryForObject("""
            SELECT EXISTS (
              SELECT 1
              FROM information_schema.columns
              WHERE table_schema = 'public'
                AND table_name = ?
                AND column_name = ?
            )
            """, Boolean.class, table, column);
        return Boolean.TRUE.equals(exists);
    }

    public void writeNewColumnWhenSafe(String table, String column, Runnable writePath) {
        if (!columnExists(table, column)) {
            throw new IllegalStateException("Schema is not expanded in this region: " + table + "." + column);
        }
        writePath.run();
    }
}
```

### Config

Use expand-migrate-contract:

```text
1. Expand: add nullable columns, new tables, backward-compatible indexes.
2. Deploy code that can read old and new shapes.
3. Backfill data idempotently.
4. Switch writes to the new shape only after all regions are expanded.
5. Contract: remove old columns only after all regions no longer need them.
```

Flyway example:

```yaml
spring:
  flyway:
    enabled: true
    out-of-order: false
    validate-on-migrate: true
```

Migration guard:

```sql
ALTER TABLE orders ADD COLUMN IF NOT EXISTS external_reference varchar(128);
-- later release only:
ALTER TABLE orders ALTER COLUMN external_reference SET NOT NULL;
```

### Validation

1. Deploy old code in one region and new code in another.
2. Run expand migration.
3. Verify both versions read and write successfully.
4. Backfill.
5. Enable new write path.
6. Fail over during each step and verify no region sees incompatible data.

---

## Case 13: Thundering Herd

### Failure Mode

After failover, every application instance reconnects at once. Connection storms overload the new writer, extending the outage. More writes time out, retry, and amplify the storm.

### RPO Impact

- **RPO target**: bounded failover uncertainty window.
- **Failure**: retry/reconnect storm increases the number of uncertain writes.
- **Symptom**: p95/p99 latency spikes, Hikari acquisition timeout, repeated retry exhaustion.

### Detection SQL

Connection pressure:

```sql
SELECT
  state,
  COUNT(*) AS connections
FROM pg_stat_activity
GROUP BY state
ORDER BY connections DESC;
```

Connection churn:

```sql
SELECT
  datname,
  numbackends,
  xact_commit,
  xact_rollback,
  blks_read,
  blks_hit
FROM pg_stat_database
WHERE datname = current_database();
```

### Java Remediation: StaggeredReconnection.java

```java
package com.multiregion.failover;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public class StaggeredReconnection {

    private static final Logger log = LoggerFactory.getLogger(StaggeredReconnection.class);

    private final HikariDataSource dataSource;
    private final Duration maxJitter;

    public StaggeredReconnection(HikariDataSource dataSource, Duration maxJitter) {
        this.dataSource = dataSource;
        this.maxJitter = maxJitter;
    }

    public void softEvictWithJitter() {
        long delay = ThreadLocalRandom.current().nextLong(maxJitter.toMillis() + 1);
        sleep(delay);
        log.info("Soft-evicting Hikari connections after jitterMs={}", delay);
        dataSource.getHikariPoolMXBean().softEvictConnections();
    }

    public void warmPool(int targetConnections) {
        for (int i = 0; i < targetConnections; i++) {
            sleep(ThreadLocalRandom.current().nextLong(25, 250));
            try (var connection = dataSource.getConnection()) {
                connection.createStatement().execute("SELECT 1");
            } catch (Exception ex) {
                log.warn("Pool warmup connection failed: {}", ex.getMessage());
            }
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
```

Retry policy should also include jitter. The current `@Retryable` policy uses exponential backoff; add randomization when moving to production-grade traffic.

### Config

Hikari:

```yaml
spring:
  datasource:
    hikari:
      minimum-idle: 0
      maximum-pool-size: 10
      connection-timeout: 5000
      validation-timeout: 2000
      initialization-fail-timeout: -1
```

Application failover:

```yaml
failover:
  reconnect:
    max-jitter-ms: 5000
    warmup-connections: 2
  retry:
    max-attempts: 5
    initial-delay-ms: 500
    max-delay-ms: 4000
    jitter: true
```

### Validation

1. Run k6 with enough VUs to keep all pools busy.
2. Kill the writer.
3. Compare p95/p99 latency and failed request rate with and without jitter.
4. Verify the new writer does not exceed connection limits during recovery.
5. Confirm retries spread over the failover window instead of aligning.

---

# Implementation Priority For This Project

The current repository already has the foundation for failover testing:

- `DatabaseConnections` creates separate `WritePool` and `ReadPool`.
- `ProductService` retries write operations with exponential backoff.
- `docs/test-scenarios.md` captures k6 failover behavior and measured failure rates.
- `/admin/topology` exposes topology and lag fields from the mock Aurora function.

Recommended next implementation order:

1. **Case 1: Replication lag health** because it turns RPO from an implicit assumption into an observable budget.
2. **Case 11: Idempotency** because retries already exist and unsafe retries can create duplicate effects.
3. **Case 2: Reconciliation ledger** because it handles the gap between client acknowledgement and promoted-region durability.
4. **Case 13: Staggered reconnection** because it improves failover behavior under load.
5. **Case 4 and Case 5** for production-grade read/query controls.

Active-active cases should remain design references unless the project intentionally changes its architecture away from a single global writer.

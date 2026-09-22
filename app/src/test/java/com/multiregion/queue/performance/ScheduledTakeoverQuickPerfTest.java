package com.multiregion.queue.performance;

import com.multiregion.queue.application.DynamicQueueListenerCoordinator;
import com.multiregion.queue.application.LocalQueueListenerCoordinator;
import com.multiregion.queue.config.QueueCoordinationProperties;
import com.multiregion.queue.config.QueueCoordinationScheduler;
import com.multiregion.queue.config.QueueListenerConfiguration;
import com.multiregion.queue.domain.QueueListenerAssignment;
import com.multiregion.queue.persistence.JdbcQueueRegionStateStore;
import com.multiregion.queue.port.QueueListenerContainer;
import com.multiregion.queue.port.QueueListenerProvisioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quickperf.annotation.MeasureExecutionTime;
import org.quickperf.junit5.QuickPerfTest;
import org.quickperf.sql.annotation.ExpectMaxDelete;
import org.quickperf.sql.annotation.ExpectMaxInsert;
import org.quickperf.sql.annotation.ExpectSelect;
import org.quickperf.sql.annotation.ExpectMaxUpdate;
import org.quickperf.sql.config.QuickPerfSqlDataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** SQL budgets cover real JDBC reads on a scheduler worker, not fixture writes. */
@QuickPerfTest
@ExpectSelect(6)
@ExpectMaxInsert(0)
@ExpectMaxUpdate(0)
@ExpectMaxDelete(0)
@MeasureExecutionTime
class ScheduledTakeoverQuickPerfTest {
    private JdbcTemplate fixture;
    private JdbcQueueRegionStateStore store;
    private final RecordingProvisioner listeners = new RecordingProvisioner();
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void database() {
        var raw = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        fixture = new JdbcTemplate(raw);
        fixture.execute("""
                CREATE TABLE queue_region_status (
                    queue_name varchar(128), region varchar(64), status varchar(16),
                    reason varchar(255), updated_at timestamp DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (queue_name, region))
                """);
        // Explicit proxy avoids the old QuickPerf Spring Boot 2 auto-configuration.
        store = new JdbcQueueRegionStateStore(
                QuickPerfSqlDataSourceBuilder.aDataSourceBuilder().buildProxy(raw), properties(1));
    }

    @org.junit.jupiter.api.AfterEach
    void closeDatabase() {
        fixture.execute("SHUTDOWN");
    }

    @Test
    void healthyRegions() throws Exception {
        run("healthy", 1, tick -> {}, new int[]{0, 0, 0, 0, 0, 0}, 0, 0);
    }

    @Test
    void persistentFailureDoesNotRecreateListeners() throws Exception {
        run("persistent-failure", 1, tick -> status("eu-west-1", "DOWN"),
                new int[]{1, 1, 1, 1, 1, 1}, 1, 0);
    }

    @Test
    void recoveryThenNewFailureStartsANewTakeover() throws Exception {
        run("recovery-and-refailure", 1,
                tick -> status("eu-west-1", tick == 2 || tick == 3 ? "UP" : "DOWN"),
                new int[]{1, 1, 0, 0, 1, 1}, 2, 1);
    }

    @Test
    void expiryStaysSuppressedUntilRecovery() throws Exception {
        run("expiry-and-rearm", 1, tick -> {
            status("eu-west-1", tick == 4 ? "UP" : "DOWN");
            if (tick == 1) clock.advance(999);
            if (tick == 2) clock.advance(1);
        }, new int[]{1, 1, 0, 0, 0, 1}, 2, 1);
    }

    @Test
    void localFailureReleasesTakeover() throws Exception {
        run("local-failure", 1, tick -> {
            status("eu-west-1", "DOWN");
            status("us-east-1", tick >= 2 ? "DOWN" : "UP");
        }, new int[]{1, 1, 0, 0, 0, 0}, 1, 1);
    }

    @Test
    void thousandQueuesKeepOneSelectPerTick() throws Exception {
        run("1000-queues", 1000, tick -> status("eu-west-1", tick < 4 ? "DOWN" : "UP"),
                new int[]{1000, 1000, 1000, 1000, 0, 0}, 1000, 1000);
    }

    @Test
    void slowListenerStartDoesNotCauseCatchUpTicks() throws Exception {
        listeners.startDelayMillis = 30;
        run("slow-listener-start", 1, tick -> status("eu-west-1", "DOWN"),
                new int[]{1, 1, 1, 1, 1, 1}, 1, 0);
    }

    private void run(String name, int queues, IntConsumer beforeTick, int[] active,
                     int expectedStarts, int expectedStops) throws Exception {
        for (int queue = 0; queue < queues; queue++) {
            for (String region : List.of("us-east-1", "eu-west-1")) {
                fixture.update("INSERT INTO queue_region_status(queue_name, region, status) VALUES (?, ?, 'UP')",
                        "queue-" + queue, region);
            }
        }
        var policy = properties(queues);
        var coordinator = new DynamicQueueListenerCoordinator("us-east-1", store, listeners, policy, clock);
        var entrypoint = new QueueCoordinationScheduler(
                new LocalQueueListenerCoordinator("us-east-1", policy, listeners, store), coordinator);
        var completed = new CompletableFuture<Void>();
        List<Long> durations = new ArrayList<>();
        List<Long> gaps = new ArrayList<>();
        // Create worker threads after QuickPerf starts recording: its SQL recorder is inherited.
        ThreadPoolTaskScheduler scheduler = new QueueListenerConfiguration().queueTaskScheduler();
        scheduler.initialize();
        var future = scheduler.scheduleWithFixedDelay(new Runnable() {
            private int tick;
            private long previousEnd;

            @Override
            public void run() {
                if (completed.isDone()) return;
                try {
                    assertThat(Thread.currentThread().getName()).startsWith("queue-coordination-");
                    long callbackStart = System.nanoTime();
                    if (previousEnd != 0) gaps.add(callbackStart - previousEnd);
                    beforeTick.accept(tick);
                    long start = System.nanoTime();
                    entrypoint.reconcileTakeoverListeners();
                    durations.add(System.nanoTime() - start);
                    assertThat(coordinator.runningAssignments()).hasSize(active[tick]);
                    tick++;
                    previousEnd = System.nanoTime();
                    if (tick == active.length) completed.complete(null);
                } catch (Throwable failure) {
                    completed.completeExceptionally(failure);
                }
            }
        }, Duration.ofMillis(10));
        try {
            completed.get(20, TimeUnit.SECONDS);
        } finally {
            future.cancel(false);
            scheduler.shutdown();
            assertThat(scheduler.getScheduledThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(durations).hasSize(6);
        assertThat(listeners.starts).isEqualTo(expectedStarts);
        assertThat(listeners.stops).isEqualTo(expectedStops);
        // Fixed-delay scheduling must wait after completion rather than catch up at a fixed rate.
        assertThat(gaps).allSatisfy(gap -> assertThat(gap).isGreaterThanOrEqualTo(10_000_000L));
        if (listeners.startDelayMillis > 0) {
            assertThat(durations.getFirst()).isGreaterThanOrEqualTo(
                    TimeUnit.MILLISECONDS.toNanos(listeners.startDelayMillis));
        }
        var sorted = durations.stream().sorted().toList();
        System.out.printf(Locale.ROOT,
                "TAKEOVER_PERF case=%s queues=%d rows=%d ticks=6 starts=%d stops=%d median_ms=%.3f max_ms=%.3f min_gap_ms=%.3f%n",
                name, queues, queues * 2, listeners.starts, listeners.stops,
                (sorted.get(2) + sorted.get(3)) / 2_000_000.0,
                sorted.getLast() / 1_000_000.0,
                gaps.stream().mapToLong(Long::longValue).min().orElseThrow() / 1_000_000.0);
    }

    private void status(String region, String status) {
        // Fault injection bypasses the measured proxy, like an external admin changing health.
        fixture.update("UPDATE queue_region_status SET status = ? WHERE region = ?", status, region);
    }

    private static QueueCoordinationProperties properties(int queues) {
        return new QueueCoordinationProperties(true, 10, 10, 1000,
                IntStream.range(0, queues).mapToObj(i -> "queue-" + i).toList(),
                List.of("us-east-1", "eu-west-1"));
    }

    private static final class RecordingProvisioner implements QueueListenerProvisioner {
        private long startDelayMillis;
        private int starts;
        private int stops;

        @Override
        public QueueListenerContainer create(QueueListenerAssignment assignment) {
            return new QueueListenerContainer() {
                private boolean running;
                public QueueListenerAssignment assignment() { return assignment; }
                public void start() {
                    try {
                        Thread.sleep(startDelayMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Listener start interrupted", interrupted);
                    }
                    starts++;
                    running = true;
                }
                public void stop() { stops++; running = false; }
                public boolean isRunning() { return running; }
            };
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(long millis) { now = now.plusMillis(millis); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        public Instant instant() { return now; }
    }
}

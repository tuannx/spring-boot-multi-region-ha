package com.multiregion.router;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bugs 2 and 3 from the experiment: with US hung, users paid the full per-try timeout until enough
 * of them had failed to eject US, and paid it again every time the cooldown ran out while US was
 * still hung. Active health checks find the hung region without user traffic.
 */
class RouterHealthCheckTest {

    private static final Duration PER_TRY = Duration.ofMillis(800);
    private static final Duration BASE_EJECTION = Duration.ofMillis(300);
    private static final long SLOW_MILLIS = 700;
    private static final int BURST = 50;

    private MockRegion us;
    private MockRegion eu;
    private RegionEndpoint usEndpoint;
    private RegionEndpoint euEndpoint;
    private ActiveHealthChecker checker;
    private RouterHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        us = new MockRegion("us-east-1");
        eu = new MockRegion("eu-west-1");
        usEndpoint = us.endpoint(0);
        euEndpoint = eu.endpoint(1);
        RouterPolicy policy = new RouterPolicy(2, BASE_EJECTION, Duration.ofSeconds(5), PER_TRY,
                Duration.ofMillis(1000), 20, 3);
        harness = new RouterHarness(new LocalityPriorityRouter(List.of(usEndpoint, euEndpoint), policy));
        checker = new ActiveHealthChecker(List.of(usEndpoint, euEndpoint), new ActiveHealthChecker.Config(
                "/actuator/health", Duration.ofMillis(100), Duration.ofMillis(150), 2, 2, BASE_EJECTION));
        checker.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        checker.close();
        harness.close();
        us.close();
        eu.close();
    }

    @Test
    void hungRegionIsFoundByProbesBeforeUsersPayForIt() throws Exception {
        us.mode.set(MockRegion.Mode.HANG);
        awaitTrue(() -> !usEndpoint.isHealthy(), "US marked unhealthy by probes");
        int usRequestsBefore = us.requests.get();

        List<RouterHarness.Result> burst = burst();

        assertEquals(0, countSlow(burst), "no request waits for the hung region");
        assertTrue(burst.stream().allMatch(r -> r.status() == 200 && "eu-west-1".equals(
                r.header(LocalityPriorityRouter.HEADER_ROUTED_REGION))));
        assertEquals(usRequestsBefore, us.requests.get(), "no user request was sent to US");
    }

    @Test
    void hungRegionStaysOutAfterEjectionTimePasses() throws Exception {
        us.mode.set(MockRegion.Mode.HANG);
        awaitTrue(() -> !usEndpoint.isHealthy(), "US marked unhealthy by probes");
        Thread.sleep(BASE_EJECTION.toMillis() * 3);

        List<RouterHarness.Result> burst = burst();

        assertEquals(0, countSlow(burst), "the old cooldown expiry no longer lets a burst into a hung region");
    }

    @Test
    void trafficReturnsToPrimaryOnceProbesPassAgain() throws Exception {
        us.mode.set(MockRegion.Mode.HANG);
        awaitTrue(() -> !usEndpoint.isHealthy(), "US marked unhealthy by probes");

        us.mode.set(MockRegion.Mode.OK);
        awaitTrue(usEndpoint::isHealthy, "US healthy again after 2 passing probes");
        RouterHarness.Result result = harness.send(harness.get("/api/products"));

        assertEquals("us-east-1", result.header(LocalityPriorityRouter.HEADER_ROUTED_REGION));
        assertEquals("false", result.header(LocalityPriorityRouter.HEADER_FAILOVER));
    }

    @Test
    void routeTimeoutBoundsTheWholeRequestWhenEveryRegionHangs() throws Exception {
        checker.close();
        us.mode.set(MockRegion.Mode.HANG);
        eu.mode.set(MockRegion.Mode.HANG);

        RouterHarness.Result result = harness.send(harness.get("/api/products"));

        assertEquals(504, result.status());
        assertEquals("route", result.header(LocalityPriorityRouter.HEADER_TIMEOUT));
        assertTrue(result.millis() < 1500, "took " + result.millis() + "ms, route timeout is 1000ms");
    }

    @Test
    void panicModeStillServesWhenEveryRegionLooksUnhealthy() throws Exception {
        checker.close();
        usEndpoint.markActiveUnhealthy();
        euEndpoint.markActiveUnhealthy();

        RouterHarness.Result result = harness.send(harness.get("/api/products"));

        assertEquals(200, result.status());
        assertEquals("true", result.header(LocalityPriorityRouter.HEADER_PANIC));
    }

    private List<RouterHarness.Result> burst() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(BURST);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<RouterHarness.Result>> futures = new ArrayList<>();
            for (int i = 0; i < BURST; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return harness.send(harness.get("/api/products"));
                }));
            }
            start.countDown();
            List<RouterHarness.Result> results = new ArrayList<>();
            for (Future<RouterHarness.Result> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long countSlow(List<RouterHarness.Result> results) {
        return results.stream().filter(r -> r.millis() >= SLOW_MILLIS).count();
    }

    private static void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting: " + what);
            }
            Thread.sleep(20);
        }
    }
}

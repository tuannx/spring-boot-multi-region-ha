package com.multiregion.router;

import io.servicetalk.http.api.StreamingHttpClient;
import io.servicetalk.http.netty.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug 1 from the experiment: a POST that reached US and was written there, but answered after the
 * per-try timeout, was replayed in EU and written twice. These tests pin the fixed behaviour.
 */
class RouterSafeRetryTest {

    private static final Duration PER_TRY = Duration.ofMillis(800);

    private MockRegion us;
    private MockRegion eu;
    private RouterHarness harness;
    private StreamingHttpClient deadClient;

    private static RouterPolicy policy() {
        return new RouterPolicy(3, Duration.ofSeconds(5), Duration.ofMinutes(5), PER_TRY,
                Duration.ofMillis(2500), 20, 3);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (harness != null) harness.close();
        if (us != null) us.close();
        if (eu != null) eu.close();
        if (deadClient != null) deadClient.closeAsync().toFuture().get();
    }

    private void startWithMocks() throws Exception {
        us = new MockRegion("us-east-1");
        eu = new MockRegion("eu-west-1");
        harness = new RouterHarness(new LocalityPriorityRouter(List.of(us.endpoint(0), eu.endpoint(1)), policy()));
    }

    private void startWithDeadPrimary(String deadHost, int deadPort) throws Exception {
        eu = new MockRegion("eu-west-1");
        deadClient = HttpClients.forSingleAddress(deadHost, deadPort).buildStreaming();
        RegionEndpoint dead = new RegionEndpoint("us-east-1", deadHost, deadPort, 0, deadClient);
        harness = new RouterHarness(new LocalityPriorityRouter(List.of(dead, eu.endpoint(1)), policy()));
    }

    @Test
    void lateAnswerToPostIsNotReplayedInSecondRegion() throws Exception {
        startWithMocks();
        us.mode.set(MockRegion.Mode.SLOW_COMMIT);

        RouterHarness.Result result = harness.send(harness.post("/api/products", "{\"name\":\"once\"}"));
        Thread.sleep(us.slowMillis);

        assertEquals(504, result.status());
        assertEquals(LocalityPriorityRouter.SKIPPED_UNSAFE_METHOD, result.header(LocalityPriorityRouter.HEADER_RETRY_SKIPPED));
        assertEquals("us-east-1", result.header(LocalityPriorityRouter.HEADER_ROUTED_REGION));
        assertEquals(1, us.writes.get(), "the write landed in US once");
        assertEquals(0, eu.writes.get(), "and was not replayed in EU");
    }

    @Test
    void lateAnswerToGetFailsOverBecauseGetIsIdempotent() throws Exception {
        startWithMocks();
        us.mode.set(MockRegion.Mode.HANG);

        RouterHarness.Result result = harness.send(harness.get("/api/products"));

        assertEquals(200, result.status());
        assertEquals("eu-west-1", result.header(LocalityPriorityRouter.HEADER_ROUTED_REGION));
        assertEquals("2", result.header(LocalityPriorityRouter.HEADER_ATTEMPTS));
    }

    @Test
    void postAnswered503IsPassedBackNotReplayed() throws Exception {
        startWithMocks();
        us.mode.set(MockRegion.Mode.UNAVAILABLE);

        RouterHarness.Result result = harness.send(harness.post("/api/products", "{}"));

        assertEquals(503, result.status());
        assertEquals("us-east-1", result.header(LocalityPriorityRouter.HEADER_ROUTED_REGION));
        assertEquals(LocalityPriorityRouter.SKIPPED_UNSAFE_METHOD, result.header(LocalityPriorityRouter.HEADER_RETRY_SKIPPED));
        assertEquals(0, eu.requests.get());
    }

    @Test
    void postFailsOverWhenPrimaryRefusesConnections() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        startWithDeadPrimary("127.0.0.1", closedPort);
        String body = "{\"name\":\"item1\",\"price\":42.0}";

        RouterHarness.Result result = harness.send(harness.post("/api/products", body));

        assertEquals(201, result.status());
        assertEquals("eu-west-1", result.header(LocalityPriorityRouter.HEADER_ROUTED_REGION));
        assertEquals("true", result.header(LocalityPriorityRouter.HEADER_FAILOVER));
        assertEquals(body, result.response().payloadBody().toString(StandardCharsets.UTF_8), "body reaches EU intact");
        assertEquals(1, eu.writes.get());
    }

    @Test
    void postFailsOverWhenPrimaryNameNoLongerResolves() throws Exception {
        // docker kill removes the container from compose DNS, so the router sees a DNS failure.
        startWithDeadPrimary("app-gone.invalid", 8080);

        RouterHarness.Result result = harness.send(harness.post("/api/products", "{}"));

        assertEquals(201, result.status());
        assertEquals("eu-west-1", result.header(LocalityPriorityRouter.HEADER_ROUTED_REGION));
        assertNull(result.header(LocalityPriorityRouter.HEADER_RETRY_SKIPPED));
    }

    @Test
    void retriesAreReleasedWhenRequestsFinish() throws Exception {
        startWithMocks();
        us.mode.set(MockRegion.Mode.UNAVAILABLE);

        for (int i = 0; i < 10; i++) {
            harness.send(harness.get("/api/products"));
        }

        assertEquals(0, harness.router.retryBudget().activeRetries());
        assertEquals(0, harness.router.retryBudget().activeRequests());
        assertTrue(eu.requests.get() > 0);
    }
}

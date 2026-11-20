package com.multiregion.router;

import io.servicetalk.http.api.StreamingHttpClient;
import io.servicetalk.http.netty.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionEndpointTest {

    private static final int THRESHOLD = 2;
    private static final long BASE = 100;
    private static final long MAX = 250;

    private final AtomicLong now = new AtomicLong(1_000);
    private StreamingHttpClient client;
    private RegionEndpoint endpoint;

    @BeforeEach
    void setUp() {
        client = HttpClients.forSingleAddress("127.0.0.1", 9).buildStreaming();
        endpoint = new RegionEndpoint("us-east-1", "127.0.0.1", 9, 0, client, now::get);
    }

    @AfterEach
    void tearDown() throws Exception {
        client.closeAsync().toFuture().get();
    }

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            endpoint.recordFailure(THRESHOLD, BASE, MAX);
        }
    }

    @Test
    void ejectsAfterThresholdAndReturnsWhenTimePasses() {
        fail(1);
        assertTrue(endpoint.isHealthy());
        fail(1);
        assertFalse(endpoint.isHealthy());

        now.addAndGet(BASE);

        assertTrue(endpoint.isHealthy());
    }

    @Test
    void eachEjectionLastsLongerUpToTheCap() {
        fail(2);
        now.addAndGet(BASE);
        fail(2);
        now.addAndGet(BASE);
        assertFalse(endpoint.isHealthy(), "second ejection lasts 2x base");
        now.addAndGet(BASE);
        assertTrue(endpoint.isHealthy());

        fail(2);
        now.addAndGet(MAX - 1);
        assertFalse(endpoint.isHealthy());
        now.addAndGet(1);
        assertTrue(endpoint.isHealthy(), "third ejection is capped at max, not 3x base");
        assertEquals(3, endpoint.ejectionCount());
    }

    @Test
    void lookingAtHealthDoesNotChangeIt() {
        fail(2);
        for (int i = 0; i < 5; i++) {
            assertFalse(endpoint.isHealthy());
        }
        assertEquals(1, endpoint.ejectionCount());
    }

    @Test
    void ejectionMultiplierWindsDownWhileHealthy() {
        fail(2);
        now.addAndGet(BASE);
        endpoint.recordSuccess(BASE);
        assertEquals(1, endpoint.ejectionCount(), "no decay right when the ejection ends");

        now.addAndGet(BASE);
        endpoint.recordSuccess(BASE);

        assertEquals(0, endpoint.ejectionCount());
    }

    @Test
    void passingProbesAfterUnhealthyEndTheEjection() {
        fail(2);
        endpoint.markActiveUnhealthy();

        endpoint.markActiveHealthy();

        assertTrue(endpoint.isHealthy());
    }
}

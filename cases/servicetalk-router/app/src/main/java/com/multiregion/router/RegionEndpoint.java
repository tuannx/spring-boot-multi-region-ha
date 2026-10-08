package com.multiregion.router;

import io.servicetalk.http.api.HttpClient;
import io.servicetalk.http.api.StreamingHttpClient;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Represents a regional backend destination with locality priority and health state.
 */
public final class RegionEndpoint {

    private final String regionName;
    private final String host;
    private final int port;
    private final int defaultPriority;
    private final StreamingHttpClient client;
    private final HttpClient httpClient;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong unhealthyUntilTimestamp = new AtomicLong(0L);

    public RegionEndpoint(String regionName, String host, int port, int defaultPriority, StreamingHttpClient client) {
        this.regionName = Objects.requireNonNull(regionName, "regionName");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.defaultPriority = defaultPriority;
        this.client = Objects.requireNonNull(client, "client");
        this.httpClient = client.asClient();
    }

    public String regionName() {
        return regionName;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public int defaultPriority() {
        return defaultPriority;
    }

    public StreamingHttpClient client() {
        return client;
    }

    public HttpClient httpClient() {
        return httpClient;
    }

    /**
     * Outlier detection check: whether the endpoint is currently considered healthy.
     */
    public boolean isHealthy() {
        long cooldownUntil = unhealthyUntilTimestamp.get();
        if (cooldownUntil == 0L) {
            return true;
        }
        if (System.currentTimeMillis() >= cooldownUntil) {
            // Cooldown expired, mark as probationary healthy
            unhealthyUntilTimestamp.set(0L);
            consecutiveFailures.set(0);
            return true;
        }
        return false;
    }

    /**
     * Record a successful response. Resets consecutive failure counter.
     */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        unhealthyUntilTimestamp.set(0L);
    }

    /**
     * Record a failure. If consecutive failures exceed threshold, mark as unhealthy for cooldown period.
     *
     * @param failureThreshold number of consecutive failures before ejecting
     * @param cooldownMillis duration in milliseconds to eject the host
     */
    public void recordFailure(int failureThreshold, long cooldownMillis) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            unhealthyUntilTimestamp.set(System.currentTimeMillis() + cooldownMillis);
        }
    }

    @Override
    public String toString() {
        return "RegionEndpoint{" +
                "region='" + regionName + '\'' +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", priority=" + defaultPriority +
                ", healthy=" + isHealthy() +
                '}';
    }
}

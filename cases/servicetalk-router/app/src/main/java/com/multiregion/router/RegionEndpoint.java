package com.multiregion.router;

import io.servicetalk.http.api.HttpClient;
import io.servicetalk.http.api.StreamingHttpClient;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * A regional backend with two health signals, combined the way Envoy combines them.
 *
 * <ul>
 *   <li>Active: set by {@link ActiveHealthChecker} from probes, so a hung region is found without
 *       sending user traffic to it.</li>
 *   <li>Passive (outlier detection): consecutive failures eject the endpoint. Ejection n lasts
 *       {@code min(base * n, max(maxEjection, base))}; the multiplier drops by one per base period
 *       spent healthy, so a flapping region stays out longer each time.</li>
 * </ul>
 *
 * <p>{@link #isHealthy()} has no side effects: an ejection ends when its time passes, it is not
 * reset by whoever happens to look.
 */
public final class RegionEndpoint {

    private final String regionName;
    private final String host;
    private final int port;
    private final int defaultPriority;
    private final StreamingHttpClient client;
    private final HttpClient httpClient;
    private final LongSupplier clockMillis;

    private volatile boolean activeHealthy = true;
    private volatile long ejectedUntilMillis;
    private int consecutiveFailures;
    private int ejectionCount;
    private long lastDecayMillis;

    public RegionEndpoint(String regionName, String host, int port, int defaultPriority, StreamingHttpClient client) {
        this(regionName, host, port, defaultPriority, client, () -> System.nanoTime() / 1_000_000L);
    }

    RegionEndpoint(String regionName, String host, int port, int defaultPriority,
                   StreamingHttpClient client, LongSupplier clockMillis) {
        this.regionName = Objects.requireNonNull(regionName, "regionName");
        this.host = Objects.requireNonNull(host, "host");
        this.port = port;
        this.defaultPriority = defaultPriority;
        this.client = Objects.requireNonNull(client, "client");
        this.httpClient = client.asClient();
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
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

    /** Healthy means the last probes passed and no ejection is running. */
    public boolean isHealthy() {
        return activeHealthy && !isEjected();
    }

    public boolean isEjected() {
        return clockMillis.getAsLong() < ejectedUntilMillis;
    }

    public boolean isActiveHealthy() {
        return activeHealthy;
    }

    synchronized int ejectionCount() {
        return ejectionCount;
    }

    /** A 2xx-4xx answer: the endpoint works, so the failure streak ends. */
    public synchronized void recordSuccess(long baseEjectionMillis) {
        consecutiveFailures = 0;
        decay(baseEjectionMillis);
    }

    /**
     * A 502/503/504 answer, a timeout or a connection error.
     *
     * @return true when this failure ejected the endpoint
     */
    public synchronized boolean recordFailure(int failureThreshold, long baseEjectionMillis, long maxEjectionMillis) {
        if (isEjected()) {
            return false;
        }
        consecutiveFailures++;
        if (consecutiveFailures < failureThreshold) {
            return false;
        }
        consecutiveFailures = 0;
        ejectionCount++;
        long cap = Math.max(maxEjectionMillis, baseEjectionMillis);
        long length = Math.min(Math.multiplyExact(baseEjectionMillis, (long) ejectionCount), cap);
        long now = clockMillis.getAsLong();
        ejectedUntilMillis = now + length;
        lastDecayMillis = now + length;
        return true;
    }

    /** Probes failed enough times in a row: stop sending traffic. */
    public synchronized void markActiveUnhealthy() {
        activeHealthy = false;
    }

    /**
     * Probes passed enough times in a row after being unhealthy. Like Envoy's
     * successful_active_health_check_uneject_host, this also ends any running ejection.
     */
    public synchronized void markActiveHealthy() {
        activeHealthy = true;
        ejectedUntilMillis = 0L;
        consecutiveFailures = 0;
    }

    /** A probe passed while already healthy: let the ejection multiplier wind down. */
    public synchronized void onProbePassed(long baseEjectionMillis) {
        decay(baseEjectionMillis);
    }

    private void decay(long baseEjectionMillis) {
        long now = clockMillis.getAsLong();
        if (ejectionCount > 0 && !isEjected() && now - lastDecayMillis >= baseEjectionMillis) {
            ejectionCount--;
            lastDecayMillis = now;
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

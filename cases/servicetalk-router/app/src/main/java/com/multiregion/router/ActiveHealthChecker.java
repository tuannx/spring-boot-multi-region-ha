package com.multiregion.router;

import io.servicetalk.http.api.HttpClient;
import io.servicetalk.http.api.HttpHeaderNames;
import io.servicetalk.http.api.HttpRequest;
import io.servicetalk.http.api.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Probes every region on a timer, like an Envoy HTTP health check. A region that hangs is marked
 * unhealthy after {@code unhealthyThreshold} failed probes, before user requests pay a timeout
 * for it, and comes back after {@code healthyThreshold} passing probes.
 */
public final class ActiveHealthChecker implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ActiveHealthChecker.class);

    /** Probe settings, named after Envoy's health_check fields. */
    public record Config(String path,
                         Duration interval,
                         Duration timeout,
                         int unhealthyThreshold,
                         int healthyThreshold,
                         Duration baseEjection) {
        public Config {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(interval, "interval");
            Objects.requireNonNull(timeout, "timeout");
            Objects.requireNonNull(baseEjection, "baseEjection");
            if (unhealthyThreshold < 1 || healthyThreshold < 1) {
                throw new IllegalArgumentException("thresholds must be >= 1");
            }
        }
    }

    private final List<Probe> probes;
    private final Config config;
    private final ScheduledExecutorService scheduler;

    public ActiveHealthChecker(List<RegionEndpoint> endpoints, Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.probes = endpoints.stream().map(Probe::new).toList();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "router-health-check");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        long periodMillis = config.interval().toMillis();
        scheduler.scheduleAtFixedRate(this::checkOnce, 0L, periodMillis, TimeUnit.MILLISECONDS);
    }

    /** Sends one probe to every region that has none in flight. */
    void checkOnce() {
        for (Probe probe : probes) {
            try {
                probe.send();
            } catch (RuntimeException e) {
                // A thrown exception would cancel the schedule and silently stop all probing.
                LOGGER.error("Health check could not send a probe", e);
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private final class Probe {
        private final RegionEndpoint endpoint;
        private final AtomicBoolean inFlight = new AtomicBoolean();
        private final AtomicInteger passes = new AtomicInteger();
        private final AtomicInteger failures = new AtomicInteger();

        Probe(RegionEndpoint endpoint) {
            this.endpoint = endpoint;
        }

        void send() {
            if (!inFlight.compareAndSet(false, true)) {
                return;
            }
            HttpClient client = endpoint.httpClient();
            HttpRequest request;
            try {
                request = client.get(config.path())
                        .setHeader(HttpHeaderNames.HOST, endpoint.host() + ":" + endpoint.port());
            } catch (RuntimeException e) {
                inFlight.set(false);
                throw e;
            }
            client.request(request)
                    .timeout(config.timeout())
                    .toCompletionStage()
                    .whenComplete((response, error) -> {
                        try {
                            boolean passed = error == null && isPass(response.status());
                            if (passed) {
                                onPass();
                            } else {
                                onFail(error != null ? error.toString() : response.status().toString());
                            }
                        } finally {
                            inFlight.set(false);
                        }
                    });
        }

        private void onPass() {
            failures.set(0);
            if (endpoint.isActiveHealthy()) {
                endpoint.onProbePassed(config.baseEjection().toMillis());
                return;
            }
            if (passes.incrementAndGet() >= config.healthyThreshold()) {
                passes.set(0);
                endpoint.markActiveHealthy();
                LOGGER.info("Health check: {} is healthy again", endpoint.regionName());
            }
        }

        private void onFail(String reason) {
            passes.set(0);
            if (!endpoint.isActiveHealthy()) {
                return;
            }
            if (failures.incrementAndGet() >= config.unhealthyThreshold()) {
                failures.set(0);
                endpoint.markActiveUnhealthy();
                LOGGER.warn("Health check: {} marked unhealthy ({})", endpoint.regionName(), reason);
            }
        }

        private boolean isPass(HttpResponseStatus status) {
            return status.statusClass() == HttpResponseStatus.StatusClass.SUCCESSFUL_2XX;
        }
    }
}

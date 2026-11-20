package com.multiregion.router;

import java.time.Duration;
import java.util.Objects;

/**
 * Tunables for the router, named after the Envoy settings they mirror.
 *
 * @param failureThreshold    consecutive failures before an endpoint is ejected (Envoy consecutive_5xx)
 * @param baseEjection        first ejection length; ejection n lasts n times this (Envoy base_ejection_time)
 * @param maxEjection         cap on one ejection (Envoy max_ejection_time)
 * @param perTryTimeout       budget for one attempt against one region (Envoy per_try_timeout)
 * @param routeTimeout        budget for the whole request including every retry (Envoy route timeout)
 * @param retryBudgetPercent  concurrent retries allowed as a percent of active requests (Envoy budget_percent)
 * @param minRetryConcurrency concurrent retries always allowed (Envoy min_retry_concurrency)
 */
public record RouterPolicy(int failureThreshold,
                           Duration baseEjection,
                           Duration maxEjection,
                           Duration perTryTimeout,
                           Duration routeTimeout,
                           int retryBudgetPercent,
                           int minRetryConcurrency) {

    public static final Duration DEFAULT_MAX_EJECTION = Duration.ofMinutes(5);
    public static final int DEFAULT_RETRY_BUDGET_PERCENT = 20;
    public static final int DEFAULT_MIN_RETRY_CONCURRENCY = 3;

    public RouterPolicy {
        Objects.requireNonNull(baseEjection, "baseEjection");
        Objects.requireNonNull(maxEjection, "maxEjection");
        Objects.requireNonNull(perTryTimeout, "perTryTimeout");
        Objects.requireNonNull(routeTimeout, "routeTimeout");
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be >= 1");
        }
        if (perTryTimeout.isNegative() || perTryTimeout.isZero()) {
            throw new IllegalArgumentException("perTryTimeout must be > 0");
        }
        if (routeTimeout.compareTo(perTryTimeout) < 0) {
            throw new IllegalArgumentException("routeTimeout must be >= perTryTimeout");
        }
        if (retryBudgetPercent < 0 || minRetryConcurrency < 0) {
            throw new IllegalArgumentException("retry budget must be >= 0");
        }
    }

    /**
     * Settings for the old constructors: same threshold, cooldown and timeout as before, and a
     * route timeout long enough for one attempt per region so existing callers keep their reach.
     */
    static RouterPolicy legacy(int failureThreshold, long cooldownMillis, Duration perTryTimeout, int regions) {
        Duration cooldown = Duration.ofMillis(cooldownMillis);
        return new RouterPolicy(failureThreshold, cooldown, cooldown, perTryTimeout,
                perTryTimeout.multipliedBy(Math.max(1, regions)),
                DEFAULT_RETRY_BUDGET_PERCENT, DEFAULT_MIN_RETRY_CONCURRENCY);
    }
}

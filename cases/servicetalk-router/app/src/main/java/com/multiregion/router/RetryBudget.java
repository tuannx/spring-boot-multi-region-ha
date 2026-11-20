package com.multiregion.router;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps concurrent retries so a failing region cannot double the load on the healthy one.
 * Mirrors Envoy's retry budget: at most {@code max(minConcurrency, percent% of active requests)}
 * retries may be in flight at once.
 */
final class RetryBudget {

    private final int percent;
    private final int minConcurrency;
    private final AtomicInteger activeRequests = new AtomicInteger();
    private final AtomicInteger activeRetries = new AtomicInteger();

    RetryBudget(int percent, int minConcurrency) {
        if (percent < 0 || minConcurrency < 0) {
            throw new IllegalArgumentException("budget must be >= 0");
        }
        this.percent = percent;
        this.minConcurrency = minConcurrency;
    }

    void requestStarted() {
        activeRequests.incrementAndGet();
    }

    void requestFinished() {
        activeRequests.decrementAndGet();
    }

    boolean tryAcquireRetry() {
        while (true) {
            int current = activeRetries.get();
            if (current >= allowedRetries()) {
                return false;
            }
            if (activeRetries.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    void releaseRetries(int count) {
        if (count > 0) {
            activeRetries.addAndGet(-count);
        }
    }

    int allowedRetries() {
        return Math.max(minConcurrency, activeRequests.get() * percent / 100);
    }

    int activeRetries() {
        return activeRetries.get();
    }

    int activeRequests() {
        return activeRequests.get();
    }
}

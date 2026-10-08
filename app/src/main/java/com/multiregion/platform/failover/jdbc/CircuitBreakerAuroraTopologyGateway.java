package com.multiregion.platform.failover.jdbc;

import com.multiregion.platform.failover.domain.PrimaryProbeResult;
import com.multiregion.platform.failover.domain.PrimaryProbeStatus;
import com.multiregion.platform.failover.domain.TopologyInstance;
import com.multiregion.platform.failover.port.AuroraTopologyGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fails topology probes fast while the shared connectivity breaker is open.
 * <p>
 * Recording is result-based, not exception-based: only an {@code UNREACHABLE}
 * probe or a failed connectivity check trips the breaker. A {@code FAILED}
 * probe (reachable database, broken query) passes through without touching
 * the breaker, so its refuse-to-promote semantics can never be masked into
 * an auto-promotion. Each call records exactly once via explicit acquire and
 * success/error sampling.
 */
@Primary
@Repository
public class CircuitBreakerAuroraTopologyGateway implements AuroraTopologyGateway {

    private final AuroraTopologyGateway delegate;
    private final CircuitBreaker breaker;

    public CircuitBreakerAuroraTopologyGateway(
            @Qualifier("jdbcAuroraTopologyGateway") AuroraTopologyGateway delegate,
            CircuitBreaker topologyConnectivityBreaker) {
        this.delegate = delegate;
        this.breaker = topologyConnectivityBreaker;
    }

    @Override
    public PrimaryProbeResult probePrimary() {
        if (!breaker.tryAcquirePermission()) {
            return PrimaryProbeResult.unreachable(
                    "circuit-open: skipping probe while the connectivity breaker is OPEN");
        }
        long start = System.nanoTime();
        try {
            PrimaryProbeResult result = delegate.probePrimary();
            if (result.status() == PrimaryProbeStatus.UNREACHABLE) {
                breaker.onError(elapsedSince(start), TimeUnit.NANOSECONDS, connectivityCause());
            } else {
                breaker.onSuccess(elapsedSince(start), TimeUnit.NANOSECONDS);
            }
            return result;
        } catch (RuntimeException unexpected) {
            breaker.onSuccess(elapsedSince(start), TimeUnit.NANOSECONDS);
            throw unexpected;
        }
    }

    @Override
    public boolean isDatabaseConnected() {
        if (!breaker.tryAcquirePermission()) {
            return false;
        }
        long start = System.nanoTime();
        try {
            boolean connected = delegate.isDatabaseConnected();
            if (connected) {
                breaker.onSuccess(elapsedSince(start), TimeUnit.NANOSECONDS);
            } else {
                breaker.onError(elapsedSince(start), TimeUnit.NANOSECONDS, connectivityCause());
            }
            return connected;
        } catch (RuntimeException unexpected) {
            breaker.onSuccess(elapsedSince(start), TimeUnit.NANOSECONDS);
            throw unexpected;
        }
    }

    @Override
    public String currentWriter() {
        return delegate.currentWriter();
    }

    @Override
    public List<TopologyInstance> topology() {
        return delegate.topology();
    }

    private static long elapsedSince(long startNanos) {
        return System.nanoTime() - startNanos;
    }

    private static RuntimeException connectivityCause() {
        return new RuntimeException("database connectivity failure observed by topology probe");
    }
}

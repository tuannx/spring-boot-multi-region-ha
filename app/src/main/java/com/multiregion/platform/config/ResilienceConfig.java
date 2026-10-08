package com.multiregion.platform.config;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Programmatic resilience4j guards for the failover path.
 * <p>
 * The topology breaker is shared by the scheduled writer-authority probe and
 * the database-connectivity check behind {@code /health}: both observe the
 * same underlying resource, so one breaker fails both fast during an outage
 * and recovers both on the first successful half-open trial. Recording is
 * result-based and lives in the decorator: only connectivity outcomes
 * (unreachable probe, failed connectivity check) trip the breaker, so a
 * non-connectivity probe failure keeps its refuse-to-promote semantics.
 * <p>
 * The write bulkhead matches the writer pool size: beyond ten concurrent
 * writes the caller is shed immediately with {@code BulkheadFullException}
 * (mapped to 429) instead of queueing on Hikari timeouts during a failover
 * reconnect storm.
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreaker topologyConnectivityBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        return CircuitBreaker.of("topologyConnectivity", config);
    }

    @Bean
    public Bulkhead productWriteBulkhead() {
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(10)
                .maxWaitDuration(Duration.ZERO)
                .build();
        return Bulkhead.of("productWrites", config);
    }
}

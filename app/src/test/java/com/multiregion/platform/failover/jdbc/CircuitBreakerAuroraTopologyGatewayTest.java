package com.multiregion.platform.failover.jdbc;

import com.multiregion.platform.failover.domain.PrimaryProbeResult;
import com.multiregion.platform.failover.domain.PrimaryProbeStatus;
import com.multiregion.platform.failover.domain.TopologyInstance;
import com.multiregion.platform.failover.port.AuroraTopologyGateway;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CircuitBreakerAuroraTopologyGatewayTest {

    @Test
    void consecutiveFailedResultsNeverOpenTheBreaker() {
        AuroraTopologyGateway delegate = mock(AuroraTopologyGateway.class);
        when(delegate.probePrimary())
                .thenReturn(PrimaryProbeResult.failed("permission denied"));
        CircuitBreaker breaker = breakerWithOpenWait(Duration.ofSeconds(30));
        CircuitBreakerAuroraTopologyGateway gateway = gateway(delegate, breaker);

        for (int i = 0; i < 3; i++) {
            PrimaryProbeResult result = gateway.probePrimary();
            assertThat(result.status()).isEqualTo(PrimaryProbeStatus.FAILED);
            assertThat(result.detail()).isEqualTo("permission denied");
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        verify(delegate, times(3)).probePrimary();
    }

    @Test
    void unreachableResultsOpenTheBreakerThenFailFastWithoutCallingDelegate() {
        AuroraTopologyGateway delegate = mock(AuroraTopologyGateway.class);
        when(delegate.probePrimary())
                .thenReturn(PrimaryProbeResult.unreachable("connection refused"));
        CircuitBreaker breaker = breakerWithOpenWait(Duration.ofSeconds(30));
        CircuitBreakerAuroraTopologyGateway gateway = gateway(delegate, breaker);

        assertThat(gateway.probePrimary().detail()).isEqualTo("connection refused");
        assertThat(gateway.probePrimary().detail()).isEqualTo("connection refused");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        PrimaryProbeResult shed = gateway.probePrimary();

        assertThat(shed.status()).isEqualTo(PrimaryProbeStatus.UNREACHABLE);
        assertThat(shed.detail()).contains("circuit-open");
        verify(delegate, times(2)).probePrimary();
    }

    @Test
    void halfOpenTrialRecoveryClosesTheBreakerAndRestoresTruth() {
        AuroraTopologyGateway delegate = mock(AuroraTopologyGateway.class);
        when(delegate.probePrimary())
                .thenReturn(PrimaryProbeResult.unreachable("connection refused"))
                .thenReturn(PrimaryProbeResult.unreachable("connection refused"))
                .thenReturn(PrimaryProbeResult.reachable("postgres-us"));
        CircuitBreaker breaker = breakerWithOpenWait(Duration.ofSeconds(30));
        CircuitBreakerAuroraTopologyGateway gateway = gateway(delegate, breaker);

        gateway.probePrimary();
        gateway.probePrimary();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        breaker.transitionToHalfOpenState();

        PrimaryProbeResult recovered = gateway.probePrimary();

        assertThat(recovered.status()).isEqualTo(PrimaryProbeStatus.REACHABLE);
        assertThat(recovered.writerId()).isEqualTo("postgres-us");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void unexpectedDelegateFailurePropagatesWithoutTrippingTheBreaker() {
        AuroraTopologyGateway delegate = mock(AuroraTopologyGateway.class);
        when(delegate.probePrimary()).thenThrow(new IllegalStateException("boom"));
        when(delegate.isDatabaseConnected()).thenThrow(new IllegalStateException("boom"));
        CircuitBreaker breaker = breakerWithOpenWait(Duration.ofSeconds(30));
        CircuitBreakerAuroraTopologyGateway gateway = gateway(delegate, breaker);

        assertThatThrownBy(gateway::probePrimary).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(gateway::isDatabaseConnected)
                .isInstanceOf(IllegalStateException.class);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void connectivityFalseTripsTheBreakerAndFailsFastWhileOpen() {
        AuroraTopologyGateway delegate = mock(AuroraTopologyGateway.class);
        when(delegate.isDatabaseConnected()).thenReturn(false);
        CircuitBreaker breaker = breakerWithOpenWait(Duration.ofSeconds(30));
        CircuitBreakerAuroraTopologyGateway gateway = gateway(delegate, breaker);

        assertThat(gateway.isDatabaseConnected()).isFalse();
        assertThat(gateway.isDatabaseConnected()).isFalse();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThat(gateway.isDatabaseConnected()).isFalse();
        verify(delegate, times(2)).isDatabaseConnected();
    }

    @Test
    void currentWriterAndTopologyPassThroughUnchanged() {
        AuroraTopologyGateway delegate = mock(AuroraTopologyGateway.class);
        List<TopologyInstance> topology = List.of(
                new TopologyInstance("postgres-us", true, 10, 0));
        when(delegate.currentWriter()).thenReturn("postgres-us");
        when(delegate.topology()).thenReturn(topology);
        CircuitBreakerAuroraTopologyGateway gateway =
                gateway(delegate, breakerWithOpenWait(Duration.ofSeconds(30)));

        assertThat(gateway.currentWriter()).isEqualTo("postgres-us");
        assertThat(gateway.topology()).isEqualTo(topology);
    }

    private static CircuitBreakerAuroraTopologyGateway gateway(
            AuroraTopologyGateway delegate, CircuitBreaker breaker) {
        return new CircuitBreakerAuroraTopologyGateway(delegate, breaker);
    }

    private static CircuitBreaker breakerWithOpenWait(Duration waitDurationInOpenState) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(waitDurationInOpenState)
                .permittedNumberOfCallsInHalfOpenState(1)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        return CircuitBreaker.of("test-topology", config);
    }
}

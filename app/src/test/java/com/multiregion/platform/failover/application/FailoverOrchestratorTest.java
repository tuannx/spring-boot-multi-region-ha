package com.multiregion.platform.failover.application;

import com.multiregion.platform.failover.domain.FailoverActivationResult;
import com.multiregion.platform.failover.domain.FailoverActivationStatus;
import com.multiregion.platform.failover.domain.FailoverCheckResult;
import com.multiregion.platform.failover.domain.FailoverCheckStatus;
import com.multiregion.platform.failover.domain.PrimaryProbeResult;
import com.multiregion.platform.failover.port.AuroraTopologyGateway;
import com.multiregion.platform.failover.port.FailoverPromotionGateway;
import com.multiregion.platform.failover.port.WriterTrafficSwitcher;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FailoverOrchestratorTest {

    @Test
    void primaryReconciliationKeepsInitialWriterWhenLocalDatabaseOwnsAuthority() {
        Fixture fixture = fixture();
        when(fixture.promotion().isLocalWriter()).thenReturn(true);
        FailoverOrchestrator orchestrator = fixture.orchestrator(true, 3, false);

        FailoverCheckResult result = orchestrator.checkPrimaryHealth();

        assertThat(result.status()).isEqualTo(FailoverCheckStatus.PRIMARY_REGION);
        assertThat(orchestrator.isActivated()).isTrue();
        verify(fixture.traffic()).restoreInitialWriter();
        verifyNoInteractions(fixture.topology());
    }

    @Test
    void reachablePrimaryKeepsSecondaryInactive() {
        Fixture fixture = fixture();
        when(fixture.topology().probePrimary())
                .thenReturn(PrimaryProbeResult.reachable("postgres-us"));
        FailoverOrchestrator orchestrator = fixture.orchestrator(false, 1, false);

        FailoverCheckResult result = orchestrator.checkPrimaryHealth();

        assertThat(result.status()).isEqualTo(FailoverCheckStatus.PRIMARY_REACHABLE);
        assertThat(result.writerId()).isEqualTo("postgres-us");
        assertThat(orchestrator.isActivated()).isFalse();
        verifyNoInteractions(fixture.promotion());
    }

    @Test
    void failedProbeNeverPromotes() {
        Fixture fixture = fixture();
        when(fixture.topology().probePrimary()).thenReturn(
                PrimaryProbeResult.failed("permission denied while reading topology"));
        FailoverOrchestrator orchestrator = fixture.orchestrator(false, 1, false);

        FailoverCheckResult result = orchestrator.checkPrimaryHealth();

        assertThat(result.status()).isEqualTo(FailoverCheckStatus.PROBE_FAILED);
        verify(fixture.promotion(), never()).enableLocalWriterMode();
    }

    @Test
    void automaticPromotionRefusesUnreachableAuthorityByDefault() {
        Fixture fixture = fixture();
        when(fixture.topology().probePrimary())
                .thenReturn(PrimaryProbeResult.unreachable("connect timeout"));
        FailoverOrchestrator orchestrator = fixture.orchestrator(false, 2, false);

        assertThat(orchestrator.checkPrimaryHealth().status())
                .isEqualTo(FailoverCheckStatus.PRIMARY_UNREACHABLE);
        FailoverCheckResult threshold = orchestrator.checkPrimaryHealth();

        assertThat(threshold.status()).isEqualTo(FailoverCheckStatus.ACTIVATION_FAILED);
        assertThat(threshold.detail()).contains("Refusing unfenced promotion");
        verify(fixture.promotion(), never()).enableLocalWriterMode();
    }

    @Test
    void explicitUnsafeOptInCanPromoteAfterConfiguredUnreachableThreshold() {
        Fixture fixture = fixture();
        when(fixture.topology().probePrimary())
                .thenReturn(PrimaryProbeResult.unreachable("connect timeout"));
        when(fixture.promotion().isLocalWriter()).thenReturn(true);
        FailoverOrchestrator orchestrator = fixture.orchestrator(false, 2, true);

        assertThat(orchestrator.checkPrimaryHealth().status())
                .isEqualTo(FailoverCheckStatus.PRIMARY_UNREACHABLE);
        FailoverCheckResult threshold = orchestrator.checkPrimaryHealth();

        assertThat(threshold.status()).isEqualTo(FailoverCheckStatus.FAILOVER_ACTIVATED);
        verify(fixture.promotion()).enableLocalWriterMode();
        verify(fixture.traffic()).activatePromotedWriter();
    }

    @Test
    void manualPromotionRefusesWhileAuthorityStillNamesOldWriter() {
        Fixture fixture = fixture();
        when(fixture.topology().probePrimary())
                .thenReturn(PrimaryProbeResult.reachable("postgres-us"));
        FailoverOrchestrator orchestrator = fixture.orchestrator(false, 1, false);

        FailoverActivationResult result = orchestrator.forceFailover();

        assertThat(result.status()).isEqualTo(FailoverActivationStatus.ACTIVATION_FAILED);
        assertThat(result.detail()).contains("still reports writer postgres-us");
        verify(fixture.promotion(), never()).enableLocalWriterMode();
    }

    @Test
    void manualPromotionSucceedsAfterAuthorityNamesLocalFailoverWriter() {
        Fixture fixture = fixture();
        authorityNamesFailoverWriter(fixture);
        when(fixture.promotion().isLocalWriter()).thenReturn(true);
        FailoverOrchestrator orchestrator = fixture.orchestrator(false, 1, false);

        FailoverActivationResult result = orchestrator.forceFailover();

        assertThat(result.status()).isEqualTo(FailoverActivationStatus.ACTIVATED);
        assertThat(orchestrator.isActivated()).isTrue();
        verify(fixture.promotion()).enableLocalWriterMode();
        verify(fixture.traffic()).activatePromotedWriter();
        verify(fixture.traffic()).isPromotedWriterActive();
    }

    @Test
    void falsePromotionPostconditionLeavesSecondaryInactive() {
        Fixture fixture = fixture();
        authorityNamesFailoverWriter(fixture);
        when(fixture.promotion().isLocalWriter()).thenReturn(false);
        FailoverOrchestrator orchestrator = fixture.orchestrator(false, 1, false);

        FailoverActivationResult result = orchestrator.forceFailover();

        assertThat(result.status()).isEqualTo(FailoverActivationStatus.ACTIVATION_FAILED);
        assertThat(orchestrator.isActivated()).isFalse();
        verify(fixture.traffic(), never()).activatePromotedWriter();
    }

    @Test
    void ambiguousPromotionResponseReconcilesOnlyAfterWriterPostcondition() {
        Fixture fixture = fixture();
        authorityNamesFailoverWriter(fixture);
        doThrow(new IllegalStateException("promotion response lost"))
                .when(fixture.promotion()).enableLocalWriterMode();
        when(fixture.promotion().isLocalWriter()).thenReturn(true);
        FailoverOrchestrator orchestrator = fixture.orchestrator(false, 1, false);

        FailoverActivationResult result = orchestrator.forceFailover();

        assertThat(result.status()).isEqualTo(FailoverActivationStatus.ACTIVATED);
        assertThat(orchestrator.isActivated()).isTrue();
    }

    @Test
    void failedTrafficSwitchLeavesApplicationInactive() {
        Fixture fixture = fixture();
        authorityNamesFailoverWriter(fixture);
        when(fixture.promotion().isLocalWriter()).thenReturn(true);
        when(fixture.traffic().isPromotedWriterActive()).thenReturn(false);
        FailoverOrchestrator orchestrator = fixture.orchestrator(false, 1, false);

        FailoverActivationResult result = orchestrator.forceFailover();

        assertThat(result.status()).isEqualTo(FailoverActivationStatus.ACTIVATION_FAILED);
        assertThat(result.detail()).contains("did not switch");
        assertThat(orchestrator.isActivated()).isFalse();
    }

    @Test
    void secondaryRestartRestoresPromotedRouteOnlyWithMatchingAuthority() {
        Fixture fixture = fixture();
        authorityNamesFailoverWriter(fixture);
        when(fixture.promotion().isLocalWriter()).thenReturn(true);
        FailoverOrchestrator orchestrator = fixture.orchestrator(false, 1, false);

        boolean active = orchestrator.reconcilePersistedState();

        assertThat(active).isTrue();
        verify(fixture.promotion(), never()).enableLocalWriterMode();
        verify(fixture.traffic()).activatePromotedWriter();
    }

    @Test
    void secondaryRestartFailsClosedWhenPersistedWriterAuthorityIsUnreachable() {
        Fixture fixture = fixture();
        when(fixture.promotion().isLocalWriter()).thenReturn(true);
        when(fixture.topology().probePrimary())
                .thenReturn(PrimaryProbeResult.unreachable("primary endpoint unavailable"));
        FailoverOrchestrator orchestrator = fixture.orchestrator(false, 1, false);

        boolean active = orchestrator.reconcilePersistedState();

        assertThat(active).isFalse();
        assertThat(orchestrator.isActivated()).isFalse();
        verify(fixture.traffic()).restoreInitialWriter();
        verify(fixture.traffic(), never()).activatePromotedWriter();
    }

    @Test
    void secondaryRestartCanRetainPromotedRouteWithExplicitUnsafeOptIn() {
        Fixture fixture = fixture();
        when(fixture.promotion().isLocalWriter()).thenReturn(true);
        when(fixture.topology().probePrimary())
                .thenReturn(PrimaryProbeResult.unreachable("primary endpoint unavailable"));
        FailoverOrchestrator orchestrator = fixture.orchestrator(false, 1, true);

        boolean active = orchestrator.reconcilePersistedState();

        assertThat(active).isTrue();
        assertThat(orchestrator.isActivated()).isTrue();
        verify(fixture.traffic()).activatePromotedWriter();
        verify(fixture.traffic(), never()).restoreInitialWriter();
    }

    @Test
    void staleSecondaryWriterFlagIsRejectedWhenAuthorityReturnedToPrimary() {
        Fixture fixture = fixture();
        when(fixture.promotion().isLocalWriter()).thenReturn(true);
        when(fixture.topology().probePrimary())
                .thenReturn(PrimaryProbeResult.reachable("postgres-us"));
        FailoverOrchestrator orchestrator = fixture.orchestrator(false, 1, false);

        boolean active = orchestrator.reconcilePersistedState();

        assertThat(active).isFalse();
        assertThat(orchestrator.isActivated()).isFalse();
        verify(fixture.traffic()).restoreInitialWriter();
    }

    @Test
    void demotedPrimaryRoutesWritesToAuthoritativePromotedWriter() {
        Fixture fixture = fixture();
        when(fixture.promotion().localWriterId()).thenReturn("postgres-us");
        when(fixture.promotion().isLocalWriter()).thenReturn(false);
        when(fixture.topology().probePrimary())
                .thenReturn(PrimaryProbeResult.reachable("postgres-eu"));
        FailoverOrchestrator orchestrator = fixture.orchestrator(true, 1, false);

        boolean active = orchestrator.reconcilePersistedState();

        assertThat(active).isTrue();
        assertThat(orchestrator.isActivated()).isTrue();
        verify(fixture.traffic()).activatePromotedWriter();
    }

    @Test
    void repeatedActivationIsIdempotent() {
        Fixture fixture = fixture();
        authorityNamesFailoverWriter(fixture);
        when(fixture.promotion().isLocalWriter()).thenReturn(true);
        FailoverOrchestrator orchestrator = fixture.orchestrator(false, 1, false);

        FailoverActivationResult first = orchestrator.forceFailover();
        FailoverActivationResult second = orchestrator.forceFailover();

        assertThat(first.status()).isEqualTo(FailoverActivationStatus.ACTIVATED);
        assertThat(second.status()).isEqualTo(FailoverActivationStatus.ALREADY_ACTIVE);
        verify(fixture.promotion(), times(1)).enableLocalWriterMode();
    }

    @Test
    void blockingMonitorProbeDoesNotHoldManualActivationLock() throws Exception {
        Fixture fixture = fixture();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        when(fixture.topology().probePrimary()).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                probeStarted.countDown();
                if (!releaseProbe.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test probe was not released");
                }
                return PrimaryProbeResult.reachable("postgres-us");
            }
            return PrimaryProbeResult.reachable("postgres-eu");
        });
        when(fixture.promotion().isLocalWriter()).thenReturn(true);
        FailoverOrchestrator orchestrator = fixture.orchestrator(false, 3, false);

        Thread monitor = new Thread(orchestrator::checkPrimaryHealth, "blocking-probe-test");
        monitor.start();
        assertThat(probeStarted.await(1, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        FailoverActivationResult result = orchestrator.forceFailover();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        releaseProbe.countDown();
        monitor.join(2000);

        assertThat(result.status()).isEqualTo(FailoverActivationStatus.ACTIVATED);
        assertThat(elapsedMillis).isLessThan(500);
        assertThat(monitor.isAlive()).isFalse();
    }

    private void authorityNamesFailoverWriter(Fixture fixture) {
        when(fixture.topology().probePrimary())
                .thenReturn(PrimaryProbeResult.reachable("postgres-eu"));
    }

    private Fixture fixture() {
        AuroraTopologyGateway topology = mock(AuroraTopologyGateway.class);
        FailoverPromotionGateway promotion = mock(FailoverPromotionGateway.class);
        WriterTrafficSwitcher traffic = mock(WriterTrafficSwitcher.class);
        when(promotion.localWriterId()).thenReturn("postgres-eu");
        when(traffic.promotedWriterId()).thenReturn("postgres-eu");
        when(traffic.isPromotedWriterActive()).thenReturn(true);
        when(traffic.isInitialWriterActive()).thenReturn(true);
        return new Fixture(topology, promotion, traffic);
    }

    private record Fixture(
            AuroraTopologyGateway topology,
            FailoverPromotionGateway promotion,
            WriterTrafficSwitcher traffic) {

        FailoverOrchestrator orchestrator(
                boolean primary,
                int failureThreshold,
                boolean allowUnfencedPromotion) {
            return new FailoverOrchestrator(
                    topology,
                    promotion,
                    traffic,
                    primary,
                    failureThreshold,
                    allowUnfencedPromotion);
        }
    }
}

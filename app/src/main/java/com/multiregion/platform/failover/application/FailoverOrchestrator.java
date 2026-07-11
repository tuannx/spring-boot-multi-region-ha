package com.multiregion.platform.failover.application;

import com.multiregion.platform.failover.domain.FailoverActivationResult;
import com.multiregion.platform.failover.domain.FailoverActivationStatus;
import com.multiregion.platform.failover.domain.FailoverCheckResult;
import com.multiregion.platform.failover.domain.PrimaryProbeResult;
import com.multiregion.platform.failover.domain.PrimaryProbeStatus;
import com.multiregion.platform.failover.port.AuroraTopologyGateway;
import com.multiregion.platform.failover.port.FailoverControl;
import com.multiregion.platform.failover.port.FailoverMonitor;
import com.multiregion.platform.failover.port.FailoverPromotionGateway;
import com.multiregion.platform.failover.port.WriterTrafficSwitcher;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class FailoverOrchestrator implements FailoverControl, FailoverMonitor {

    private final AuroraTopologyGateway topologyGateway;
    private final FailoverPromotionGateway promotionGateway;
    private final WriterTrafficSwitcher trafficSwitcher;
    private final boolean primary;
    private final int failureThreshold;
    private final boolean allowUnfencedPromotion;
    private final AtomicBoolean activated;
    private final AtomicBoolean activationInProgress = new AtomicBoolean();
    private final AtomicInteger consecutiveUnreachable = new AtomicInteger();

    public FailoverOrchestrator(
            AuroraTopologyGateway topologyGateway,
            FailoverPromotionGateway promotionGateway,
            WriterTrafficSwitcher trafficSwitcher,
            boolean primary,
            int failureThreshold,
            boolean allowUnfencedPromotion) {
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold must be at least 1");
        }
        this.topologyGateway = topologyGateway;
        this.promotionGateway = promotionGateway;
        this.trafficSwitcher = trafficSwitcher;
        this.primary = primary;
        this.failureThreshold = failureThreshold;
        this.allowUnfencedPromotion = allowUnfencedPromotion;
        this.activated = new AtomicBoolean(primary);
    }

    @Override
    public FailoverActivationResult forceFailover() {
        if (activated.get()) {
            return FailoverActivationResult.alreadyActive();
        }

        if (!activationInProgress.compareAndSet(false, true)) {
            return FailoverActivationResult.failed("Failover activation is already in progress");
        }

        try {
            if (activated.get()) {
                return FailoverActivationResult.alreadyActive();
            }
            String authorityFailure = promotionAuthorityFailure();
            if (authorityFailure != null) {
                return FailoverActivationResult.failed(authorityFailure);
            }
            promotionGateway.enableLocalWriterMode();
            if (!promotionGateway.isLocalWriter()) {
                return FailoverActivationResult.failed(
                        "Local database did not report writer mode after promotion");
            }
            return activateApplicationTraffic();
        } catch (RuntimeException failure) {
            return resolveAmbiguousPromotion(failure);
        } finally {
            activationInProgress.set(false);
        }
    }

    @Override
    public boolean isActivated() {
        return activated.get();
    }

    @Override
    public boolean reconcilePersistedState() {
        if (!activationInProgress.compareAndSet(false, true)) {
            return activated.get();
        }

        try {
            String localWriterId = promotionGateway.localWriterId();
            boolean localWriter = promotionGateway.isLocalWriter();

            if (primary) {
                return reconcilePrimaryRoute(localWriterId, localWriter);
            }

            if (!localWriter || promotionAuthorityFailure(localWriterId) != null) {
                deactivateAndRestoreInitialWriter();
                return false;
            }
            FailoverActivationResult activation = activateApplicationTraffic();
            if (!activation.successful()) {
                throw new IllegalStateException(activation.detail());
            }
            return true;
        } finally {
            activationInProgress.set(false);
        }
    }

    @Override
    public FailoverCheckResult checkPrimaryHealth() {
        if (primary) {
            try {
                return reconcilePersistedState()
                        ? FailoverCheckResult.primaryRegion()
                        : FailoverCheckResult.activationFailed(
                                "Primary compute could not route to an authoritative writer");
            } catch (RuntimeException reconciliationFailure) {
                return FailoverCheckResult.probeFailed(reconciliationFailure.getMessage());
            }
        }
        if (activated.get()) {
            try {
                return reconcilePersistedState()
                        ? FailoverCheckResult.alreadyActive()
                        : FailoverCheckResult.activationFailed(
                                "Persisted local writer no longer has authoritative ownership");
            } catch (RuntimeException reconciliationFailure) {
                return FailoverCheckResult.probeFailed(reconciliationFailure.getMessage());
            }
        }

        PrimaryProbeResult probe;
        try {
            probe = topologyGateway.probePrimary();
        } catch (RuntimeException probeFailure) {
            consecutiveUnreachable.set(0);
            return FailoverCheckResult.probeFailed(probeFailure.getMessage());
        }

        if (activated.get()) {
            return FailoverCheckResult.alreadyActive();
        }

        return switch (probe.status()) {
            case REACHABLE -> primaryReachable(probe);
            case FAILED -> probeFailed(probe);
            case UNREACHABLE -> primaryUnreachable(probe);
        };
    }

    private FailoverCheckResult primaryReachable(PrimaryProbeResult probe) {
        consecutiveUnreachable.set(0);
        return FailoverCheckResult.primaryReachable(probe.writerId());
    }

    private FailoverCheckResult probeFailed(PrimaryProbeResult probe) {
        consecutiveUnreachable.set(0);
        return FailoverCheckResult.probeFailed(probe.detail());
    }

    private FailoverCheckResult primaryUnreachable(PrimaryProbeResult probe) {
        int failures = consecutiveUnreachable.updateAndGet(
                current -> Math.min(failureThreshold, current + 1));
        if (failures < failureThreshold) {
            return FailoverCheckResult.primaryUnreachable(
                    probe.detail(),
                    failures,
                    failureThreshold);
        }

        FailoverActivationResult activation = forceFailover();
        if (!activation.successful()) {
            return FailoverCheckResult.activationFailed(activation.detail());
        }
        if (activation.status() == FailoverActivationStatus.ALREADY_ACTIVE) {
            return FailoverCheckResult.alreadyActive();
        }
        return FailoverCheckResult.failoverActivated(probe.detail());
    }

    private FailoverActivationResult resolveAmbiguousPromotion(RuntimeException failure) {
        try {
            if (promotionGateway.isLocalWriter()) {
                FailoverActivationResult activation = activateApplicationTraffic();
                if (activation.successful()) {
                    return activation;
                }
                failure.addSuppressed(new IllegalStateException(activation.detail()));
            }
        } catch (RuntimeException verificationFailure) {
            failure.addSuppressed(verificationFailure);
        }
        return FailoverActivationResult.failed(failure.getMessage());
    }

    private FailoverActivationResult activateApplicationTraffic() {
        trafficSwitcher.activatePromotedWriter();
        if (!trafficSwitcher.isPromotedWriterActive()) {
            return FailoverActivationResult.failed(
                    "Application write traffic did not switch to the promoted writer");
        }
        activated.set(true);
        consecutiveUnreachable.set(0);
        return FailoverActivationResult.activated();
    }

    private boolean reconcilePrimaryRoute(String localWriterId, boolean localWriter) {
        if (localWriter) {
            trafficSwitcher.restoreInitialWriter();
            if (!trafficSwitcher.isInitialWriterActive()) {
                activated.set(false);
                return false;
            }
            activated.set(true);
            consecutiveUnreachable.set(0);
            return true;
        }

        PrimaryProbeResult authority = safeProbePrimary();
        if (authority.status() == PrimaryProbeStatus.REACHABLE
                && !localWriterId.equals(authority.writerId())
                && trafficSwitcher.promotedWriterId().equals(authority.writerId())) {
            FailoverActivationResult activation = activateApplicationTraffic();
            return activation.successful();
        }

        activated.set(false);
        return false;
    }

    private String promotionAuthorityFailure() {
        return promotionAuthorityFailure(promotionGateway.localWriterId());
    }

    private String promotionAuthorityFailure(String localWriterId) {
        if (!trafficSwitcher.promotedWriterId().equals(localWriterId)) {
            return "Configured promoted writer does not match local failover target: "
                    + trafficSwitcher.promotedWriterId() + " != " + localWriterId;
        }

        PrimaryProbeResult authority = safeProbePrimary();
        return switch (authority.status()) {
            case REACHABLE -> localWriterId.equals(authority.writerId())
                    ? null
                    : "Refusing promotion because authoritative topology still reports writer "
                            + authority.writerId();
            case UNREACHABLE -> allowUnfencedPromotion
                    ? null
                    : "Refusing unfenced promotion while writer authority is unreachable";
            case FAILED -> "Refusing promotion because writer authority probe failed: "
                    + authority.detail();
        };
    }

    private PrimaryProbeResult safeProbePrimary() {
        try {
            PrimaryProbeResult result = topologyGateway.probePrimary();
            return result == null
                    ? PrimaryProbeResult.failed("Writer authority probe returned no result")
                    : result;
        } catch (RuntimeException failure) {
            return PrimaryProbeResult.failed(failure.getMessage());
        }
    }

    private void deactivateAndRestoreInitialWriter() {
        trafficSwitcher.restoreInitialWriter();
        activated.set(false);
        consecutiveUnreachable.set(0);
    }
}

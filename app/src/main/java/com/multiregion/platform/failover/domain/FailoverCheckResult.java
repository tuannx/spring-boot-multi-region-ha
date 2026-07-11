package com.multiregion.platform.failover.domain;

public record FailoverCheckResult(
        FailoverCheckStatus status,
        String writerId,
        String detail
) {

    public static FailoverCheckResult primaryRegion() {
        return new FailoverCheckResult(
                FailoverCheckStatus.PRIMARY_REGION,
                null,
                "Primary compute writer route is reconciled");
    }

    public static FailoverCheckResult alreadyActive() {
        return new FailoverCheckResult(
                FailoverCheckStatus.ALREADY_ACTIVE,
                null,
                "Failover target is already active");
    }

    public static FailoverCheckResult primaryReachable(String writerId) {
        return new FailoverCheckResult(
                FailoverCheckStatus.PRIMARY_REACHABLE,
                writerId,
                "Primary writer is reachable");
    }

    public static FailoverCheckResult primaryUnreachable(
            String detail,
            int consecutiveFailures,
            int failureThreshold) {
        return new FailoverCheckResult(
                FailoverCheckStatus.PRIMARY_UNREACHABLE,
                null,
                "%s (%d/%d consecutive failures)".formatted(
                        detail,
                        consecutiveFailures,
                        failureThreshold));
    }

    public static FailoverCheckResult probeFailed(String detail) {
        return new FailoverCheckResult(
                FailoverCheckStatus.PROBE_FAILED,
                null,
                detail);
    }

    public static FailoverCheckResult failoverActivated(String probeFailure) {
        return new FailoverCheckResult(
                FailoverCheckStatus.FAILOVER_ACTIVATED,
                null,
                probeFailure);
    }

    public static FailoverCheckResult activationFailed(String detail) {
        return new FailoverCheckResult(
                FailoverCheckStatus.ACTIVATION_FAILED,
                null,
                detail);
    }
}

package com.multiregion.platform.failover.domain;

public record FailoverActivationResult(
        FailoverActivationStatus status,
        String detail
) {

    public static FailoverActivationResult activated() {
        return new FailoverActivationResult(
                FailoverActivationStatus.ACTIVATED,
                "Failover target activated");
    }

    public static FailoverActivationResult alreadyActive() {
        return new FailoverActivationResult(
                FailoverActivationStatus.ALREADY_ACTIVE,
                "Failover target is already active");
    }

    public static FailoverActivationResult failed(String detail) {
        return new FailoverActivationResult(
                FailoverActivationStatus.ACTIVATION_FAILED,
                detail);
    }

    public boolean successful() {
        return status != FailoverActivationStatus.ACTIVATION_FAILED;
    }
}

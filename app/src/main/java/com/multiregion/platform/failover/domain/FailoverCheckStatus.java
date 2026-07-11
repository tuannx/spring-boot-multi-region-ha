package com.multiregion.platform.failover.domain;

public enum FailoverCheckStatus {
    PRIMARY_REGION,
    ALREADY_ACTIVE,
    PRIMARY_REACHABLE,
    PRIMARY_UNREACHABLE,
    PROBE_FAILED,
    FAILOVER_ACTIVATED,
    ACTIVATION_FAILED
}

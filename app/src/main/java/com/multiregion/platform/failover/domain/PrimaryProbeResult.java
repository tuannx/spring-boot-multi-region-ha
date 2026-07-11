package com.multiregion.platform.failover.domain;

public record PrimaryProbeResult(
        PrimaryProbeStatus status,
        String writerId,
        String detail
) {

    public static PrimaryProbeResult reachable(String writerId) {
        return new PrimaryProbeResult(
                PrimaryProbeStatus.REACHABLE,
                writerId,
                "Primary writer is reachable");
    }

    public static PrimaryProbeResult unreachable(String detail) {
        return new PrimaryProbeResult(
                PrimaryProbeStatus.UNREACHABLE,
                null,
                detail);
    }

    public static PrimaryProbeResult failed(String detail) {
        return new PrimaryProbeResult(
                PrimaryProbeStatus.FAILED,
                null,
                detail);
    }
}

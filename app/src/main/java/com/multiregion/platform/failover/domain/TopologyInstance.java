package com.multiregion.platform.failover.domain;

public record TopologyInstance(
        String serverId,
        boolean isWriter,
        Object cpu,
        Object lagMs
) {
}

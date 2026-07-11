package com.multiregion.queue.domain;

import java.time.Instant;

public record QueueRegionState(
        String queueName,
        String region,
        QueueHealthStatus status,
        String reason,
        Instant updatedAt
) {

    public static QueueRegionState up(String queueName, String region) {
        return new QueueRegionState(queueName, region, QueueHealthStatus.UP, null, Instant.now());
    }

    public static QueueRegionState down(String queueName, String region) {
        return new QueueRegionState(queueName, region, QueueHealthStatus.DOWN, null, Instant.now());
    }

    public boolean isUp() {
        return QueueHealthStatus.UP.equals(status);
    }

    public boolean isDown() {
        return QueueHealthStatus.DOWN.equals(status);
    }
}

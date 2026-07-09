package com.multiregion.queue.domain;

public record QueueListenerAssignment(
        String queueName,
        String ownerRegion,
        ListenerMode mode
) {
}

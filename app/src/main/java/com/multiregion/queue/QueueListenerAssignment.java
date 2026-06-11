package com.multiregion.queue;

public record QueueListenerAssignment(
        String queueName,
        String ownerRegion,
        ListenerMode mode
) {
}

package com.multiregion.queue.config;

import com.multiregion.queue.application.DynamicQueueListenerCoordinator;
import com.multiregion.queue.application.LocalQueueListenerCoordinator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "queues", name = "enabled", havingValue = "true", matchIfMissing = true)
public class QueueCoordinationScheduler {

    private final LocalQueueListenerCoordinator localCoordinator;
    private final DynamicQueueListenerCoordinator takeoverCoordinator;

    public QueueCoordinationScheduler(
            LocalQueueListenerCoordinator localCoordinator,
            DynamicQueueListenerCoordinator takeoverCoordinator) {
        this.localCoordinator = localCoordinator;
        this.takeoverCoordinator = takeoverCoordinator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startLocalListeners() {
        localCoordinator.reconcile();
    }

    @Scheduled(fixedDelayString = "${queues.takeover-poll-interval-ms:60000}")
    public void reconcileLocalListeners() {
        localCoordinator.reconcile();
    }

    @Scheduled(fixedDelayString = "${queues.takeover-poll-interval-ms:60000}")
    public void reconcileTakeoverListeners() {
        takeoverCoordinator.reconcile();
    }
}

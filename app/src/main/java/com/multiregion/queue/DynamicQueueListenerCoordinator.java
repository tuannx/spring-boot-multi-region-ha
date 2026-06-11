package com.multiregion.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "queues", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DynamicQueueListenerCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DynamicQueueListenerCoordinator.class);

    private final String localRegion;
    private final QueueRegionStateRepository repository;
    private final QueueListenerContainerFactory containerFactory;
    private final QueueCoordinationProperties properties;
    private final QueueTakeoverPlanner planner;
    private final Map<QueueListenerAssignment, QueueListenerContainer> runningContainers = new HashMap<>();
    private final Map<QueueListenerAssignment, Instant> takeoverStartedAt = new HashMap<>();
    private final Set<QueueListenerAssignment> expiredTakeovers = new HashSet<>();

    public DynamicQueueListenerCoordinator(
            @Value("${AWS_REGION:us-east-1}") String localRegion,
            QueueRegionStateRepository repository,
            QueueListenerContainerFactory containerFactory,
            QueueCoordinationProperties properties) {
        this.localRegion = localRegion;
        this.repository = repository;
        this.containerFactory = containerFactory;
        this.properties = properties;
        this.planner = new QueueTakeoverPlanner();
    }

    @Scheduled(fixedDelayString = "${queues.takeover-poll-interval-ms:60000}")
    public synchronized void reconcile() {
        List<QueueRegionState> states = repository.findAll();
        Set<QueueListenerAssignment> desired = new HashSet<>(planner.plan(localRegion, states));
        Instant now = Instant.now();
        expiredTakeovers.retainAll(desired);

        runningContainers.entrySet().removeIf(entry -> {
            QueueListenerAssignment assignment = entry.getKey();
            if (desired.contains(assignment) && !isExpired(assignment, now)) {
                return false;
            }
            if (desired.contains(assignment)) {
                expiredTakeovers.add(assignment);
            }
            entry.getValue().stop();
            takeoverStartedAt.remove(assignment);
            log.info("Queue takeover assignment released: localRegion={} queue={} ownerRegion={} mode={}",
                    localRegion, assignment.queueName(), assignment.ownerRegion(), assignment.mode());
            return true;
        });

        for (QueueListenerAssignment assignment : desired) {
            if (assignment.mode() != ListenerMode.TAKEOVER) {
                continue;
            }
            if (expiredTakeovers.contains(assignment)) {
                continue;
            }
            runningContainers.computeIfAbsent(assignment, this::startContainer);
        }
    }

    public synchronized List<QueueListenerAssignment> runningAssignments() {
        return runningContainers.keySet().stream()
                .sorted(QueueAssignmentComparators.BY_QUEUE_OWNER_MODE)
                .toList();
    }

    private QueueListenerContainer startContainer(QueueListenerAssignment assignment) {
        QueueListenerContainer container = containerFactory.create(assignment);
        container.start();
        takeoverStartedAt.put(assignment, Instant.now());
        log.info("Queue assignment active: localRegion={} queue={} ownerRegion={} mode={}",
                localRegion, assignment.queueName(), assignment.ownerRegion(), assignment.mode());
        return container;
    }

    private boolean isExpired(QueueListenerAssignment assignment, Instant now) {
        Instant startedAt = takeoverStartedAt.get(assignment);
        if (startedAt == null) {
            return false;
        }
        return Duration.between(startedAt, now).toMillis() >= properties.getTakeoverMaxDurationMs();
    }
}

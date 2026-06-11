package com.multiregion.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "queues", name = "enabled", havingValue = "true", matchIfMissing = true)
public class LocalQueueListenerCoordinator {

    private static final Logger log = LoggerFactory.getLogger(LocalQueueListenerCoordinator.class);

    private final String localRegion;
    private final QueueCoordinationProperties properties;
    private final QueueListenerContainerFactory containerFactory;
    private final QueueRegionStateRepository repository;
    private final Map<QueueListenerAssignment, QueueListenerContainer> runningContainers = new HashMap<>();

    public LocalQueueListenerCoordinator(
            @Value("${AWS_REGION:us-east-1}") String localRegion,
            QueueCoordinationProperties properties,
            QueueListenerContainerFactory containerFactory,
            QueueRegionStateRepository repository) {
        this.localRegion = localRegion;
        this.properties = properties;
        this.containerFactory = containerFactory;
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void startLocalListeners() {
        reconcileLocalListeners();
    }

    @Scheduled(fixedDelayString = "${queues.takeover-poll-interval-ms:60000}")
    public synchronized void reconcileLocalListeners() {
        Set<QueueListenerAssignment> desired = desiredLocalAssignments(repository.findAll());

        runningContainers.entrySet().removeIf(entry -> {
            if (desired.contains(entry.getKey())) {
                return false;
            }
            QueueListenerAssignment assignment = entry.getKey();
            entry.getValue().stop();
            log.info("Local queue assignment released: localRegion={} queue={} ownerRegion={} mode={}",
                    localRegion, assignment.queueName(), assignment.ownerRegion(), assignment.mode());
            return true;
        });

        for (QueueListenerAssignment assignment : desired) {
            runningContainers.computeIfAbsent(assignment, this::startContainer);
        }
    }

    private Set<QueueListenerAssignment> desiredLocalAssignments(List<QueueRegionState> states) {
        Set<QueueListenerAssignment> desired = new HashSet<>();
        for (String queueName : properties.getNames()) {
            boolean localQueueIsDown = states.stream()
                    .anyMatch(state -> state.queueName().equals(queueName)
                            && state.region().equals(localRegion)
                            && state.isDown());
            if (localQueueIsDown) {
                continue;
            }
            QueueListenerAssignment assignment = new QueueListenerAssignment(
                    queueName,
                    localRegion,
                    ListenerMode.PRIMARY
            );
            desired.add(assignment);
        }
        return desired;
    }

    public synchronized List<QueueListenerAssignment> runningAssignments() {
        return runningContainers.keySet().stream()
                .sorted(QueueAssignmentComparators.BY_QUEUE_OWNER_MODE)
                .toList();
    }

    private QueueListenerContainer startContainer(QueueListenerAssignment assignment) {
        QueueListenerContainer container = containerFactory.create(assignment);
        container.start();
        log.info("Local queue assignment active: localRegion={} queue={} ownerRegion={} mode={}",
                localRegion, assignment.queueName(), assignment.ownerRegion(), assignment.mode());
        return container;
    }
}

package com.multiregion.queue.application;

import com.multiregion.queue.domain.ListenerMode;
import com.multiregion.queue.domain.QueueAssignmentComparators;
import com.multiregion.queue.domain.QueueListenerAssignment;
import com.multiregion.queue.domain.QueueRegionState;
import com.multiregion.queue.domain.QueueTakeoverPlanner;
import com.multiregion.queue.port.QueueAssignmentCoordinator;
import com.multiregion.queue.port.QueueCoordinationPolicy;
import com.multiregion.queue.port.QueueListenerContainer;
import com.multiregion.queue.port.QueueListenerProvisioner;
import com.multiregion.queue.port.QueueRegionStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DynamicQueueListenerCoordinator implements QueueAssignmentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DynamicQueueListenerCoordinator.class);

    private final String localRegion;
    private final QueueRegionStateStore stateStore;
    private final QueueListenerProvisioner listenerProvisioner;
    private final QueueCoordinationPolicy policy;
    private final QueueTakeoverPlanner planner;
    private final Clock clock;
    private final Map<QueueListenerAssignment, QueueListenerContainer> runningContainers = new HashMap<>();
    private final Map<QueueListenerAssignment, Instant> takeoverStartedAt = new HashMap<>();
    private final Set<QueueListenerAssignment> expiredTakeovers = new HashSet<>();

    public DynamicQueueListenerCoordinator(
            String localRegion,
            QueueRegionStateStore stateStore,
            QueueListenerProvisioner listenerProvisioner,
            QueueCoordinationPolicy policy,
            Clock clock) {
        this.localRegion = localRegion;
        this.stateStore = stateStore;
        this.listenerProvisioner = listenerProvisioner;
        this.policy = policy;
        this.planner = new QueueTakeoverPlanner();
        this.clock = clock;
    }

    @Override
    public synchronized void reconcile() {
        List<QueueRegionState> states = stateStore.findAll();
        Set<QueueListenerAssignment> desired = new HashSet<>(planner.plan(localRegion, states));
        Instant now = clock.instant();
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
            if (assignment.mode() != ListenerMode.TAKEOVER || expiredTakeovers.contains(assignment)) {
                continue;
            }
            runningContainers.computeIfAbsent(assignment, this::startContainer);
        }
    }

    @Override
    public synchronized List<QueueListenerAssignment> runningAssignments() {
        return runningContainers.keySet().stream()
                .sorted(QueueAssignmentComparators.BY_QUEUE_OWNER_MODE)
                .toList();
    }

    @Override
    public ListenerMode mode() {
        return ListenerMode.TAKEOVER;
    }

    private QueueListenerContainer startContainer(QueueListenerAssignment assignment) {
        QueueListenerContainer container = listenerProvisioner.create(assignment);
        container.start();
        takeoverStartedAt.put(assignment, clock.instant());
        log.info("Queue assignment active: localRegion={} queue={} ownerRegion={} mode={}",
                localRegion, assignment.queueName(), assignment.ownerRegion(), assignment.mode());
        return container;
    }

    private boolean isExpired(QueueListenerAssignment assignment, Instant now) {
        Instant startedAt = takeoverStartedAt.get(assignment);
        return startedAt != null
                && Duration.between(startedAt, now).toMillis() >= policy.takeoverMaxDurationMs();
    }
}

package com.multiregion.queue.application;

import com.multiregion.queue.domain.ListenerMode;
import com.multiregion.queue.domain.QueueAssignmentComparators;
import com.multiregion.queue.domain.QueueListenerAssignment;
import com.multiregion.queue.domain.QueueRegionState;
import com.multiregion.queue.port.QueueAssignmentCoordinator;
import com.multiregion.queue.port.QueueCoordinationPolicy;
import com.multiregion.queue.port.QueueListenerContainer;
import com.multiregion.queue.port.QueueListenerProvisioner;
import com.multiregion.queue.port.QueueRegionStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LocalQueueListenerCoordinator implements QueueAssignmentCoordinator {

    private static final Logger log = LoggerFactory.getLogger(LocalQueueListenerCoordinator.class);

    private final String localRegion;
    private final QueueCoordinationPolicy policy;
    private final QueueListenerProvisioner listenerProvisioner;
    private final QueueRegionStateStore stateStore;
    private final Map<QueueListenerAssignment, QueueListenerContainer> runningContainers = new HashMap<>();

    public LocalQueueListenerCoordinator(
            String localRegion,
            QueueCoordinationPolicy policy,
            QueueListenerProvisioner listenerProvisioner,
            QueueRegionStateStore stateStore) {
        this.localRegion = localRegion;
        this.policy = policy;
        this.listenerProvisioner = listenerProvisioner;
        this.stateStore = stateStore;
    }

    public synchronized void startLocalListeners() {
        reconcile();
    }

    @Override
    public synchronized void reconcile() {
        Set<QueueListenerAssignment> desired = desiredLocalAssignments(stateStore.findAll());

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

    @Override
    public synchronized List<QueueListenerAssignment> runningAssignments() {
        return runningContainers.keySet().stream()
                .sorted(QueueAssignmentComparators.BY_QUEUE_OWNER_MODE)
                .toList();
    }

    @Override
    public ListenerMode mode() {
        return ListenerMode.PRIMARY;
    }

    private Set<QueueListenerAssignment> desiredLocalAssignments(List<QueueRegionState> states) {
        Set<QueueListenerAssignment> desired = new HashSet<>();
        for (String queueName : policy.names()) {
            boolean localQueueIsDown = states.stream()
                    .anyMatch(state -> state.queueName().equals(queueName)
                            && state.region().equals(localRegion)
                            && state.isDown());
            if (!localQueueIsDown) {
                desired.add(new QueueListenerAssignment(queueName, localRegion, ListenerMode.PRIMARY));
            }
        }
        return desired;
    }

    private QueueListenerContainer startContainer(QueueListenerAssignment assignment) {
        QueueListenerContainer container = listenerProvisioner.create(assignment);
        container.start();
        log.info("Local queue assignment active: localRegion={} queue={} ownerRegion={} mode={}",
                localRegion, assignment.queueName(), assignment.ownerRegion(), assignment.mode());
        return container;
    }
}

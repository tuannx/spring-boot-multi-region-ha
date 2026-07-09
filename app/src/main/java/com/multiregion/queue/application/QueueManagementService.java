package com.multiregion.queue.application;

import com.multiregion.queue.domain.ListenerMode;
import com.multiregion.queue.domain.QueueAssignmentComparators;
import com.multiregion.queue.domain.QueueHealthStatus;
import com.multiregion.queue.domain.QueueListenerAssignment;
import com.multiregion.queue.port.QueueAssignmentCoordinator;
import com.multiregion.queue.port.QueueManagementUseCase;
import com.multiregion.queue.port.QueueRegionStateStore;
import com.multiregion.queue.port.QueueSnapshot;

import java.util.Comparator;
import java.util.List;

public class QueueManagementService implements QueueManagementUseCase {

    private final QueueRegionStateStore stateStore;
    private final List<QueueAssignmentCoordinator> coordinators;

    public QueueManagementService(
            QueueRegionStateStore stateStore,
            List<QueueAssignmentCoordinator> coordinators) {
        this.stateStore = stateStore;
        this.coordinators = coordinators.stream()
                .sorted(Comparator.comparing(QueueAssignmentCoordinator::mode))
                .toList();
    }

    @Override
    public QueueSnapshot snapshot() {
        List<QueueListenerAssignment> local = assignmentsFor(ListenerMode.PRIMARY);
        List<QueueListenerAssignment> takeover = assignmentsFor(ListenerMode.TAKEOVER);
        List<QueueListenerAssignment> running = coordinators.stream()
                .flatMap(coordinator -> coordinator.runningAssignments().stream())
                .sorted(QueueAssignmentComparators.BY_QUEUE_OWNER_MODE)
                .toList();
        return new QueueSnapshot(stateStore.findAll(), local, takeover, running);
    }

    @Override
    public QueueSnapshot updateStatus(
            String queueName,
            String region,
            QueueHealthStatus status,
            String reason) {
        stateStore.updateStatus(queueName, region, status, reason);
        coordinators.forEach(QueueAssignmentCoordinator::reconcile);
        return snapshot();
    }

    private List<QueueListenerAssignment> assignmentsFor(ListenerMode mode) {
        return coordinators.stream()
                .filter(coordinator -> coordinator.mode() == mode)
                .flatMap(coordinator -> coordinator.runningAssignments().stream())
                .sorted(QueueAssignmentComparators.BY_QUEUE_OWNER_MODE)
                .toList();
    }
}

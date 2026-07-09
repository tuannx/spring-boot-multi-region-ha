package com.multiregion.queue.application;

import com.multiregion.queue.domain.ListenerMode;
import com.multiregion.queue.domain.QueueHealthStatus;
import com.multiregion.queue.port.QueueAssignmentCoordinator;
import com.multiregion.queue.port.QueueRegionStateStore;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueueManagementServiceTest {

    @Test
    void persistsStatusBeforeReconcilingPrimaryThenTakeoverAssignments() {
        QueueRegionStateStore stateStore = mock(QueueRegionStateStore.class);
        QueueAssignmentCoordinator local = coordinator(ListenerMode.PRIMARY);
        QueueAssignmentCoordinator takeover = coordinator(ListenerMode.TAKEOVER);
        when(stateStore.findAll()).thenReturn(List.of());
        QueueManagementService service = new QueueManagementService(
                stateStore, List.of(takeover, local));

        service.updateStatus("orders", "eu-west-1", QueueHealthStatus.DOWN, "test outage");

        InOrder order = inOrder(stateStore, local, takeover);
        order.verify(stateStore).updateStatus(
                "orders", "eu-west-1", QueueHealthStatus.DOWN, "test outage");
        order.verify(local).reconcile();
        order.verify(takeover).reconcile();
    }

    private QueueAssignmentCoordinator coordinator(ListenerMode mode) {
        QueueAssignmentCoordinator coordinator = mock(QueueAssignmentCoordinator.class);
        when(coordinator.mode()).thenReturn(mode);
        when(coordinator.runningAssignments()).thenReturn(List.of());
        return coordinator;
    }
}

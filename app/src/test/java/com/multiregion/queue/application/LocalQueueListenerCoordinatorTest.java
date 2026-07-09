package com.multiregion.queue.application;

import com.multiregion.queue.domain.ListenerMode;
import com.multiregion.queue.domain.QueueListenerAssignment;
import com.multiregion.queue.domain.QueueRegionState;
import com.multiregion.queue.port.QueueCoordinationPolicy;
import com.multiregion.queue.port.QueueListenerContainer;
import com.multiregion.queue.port.QueueListenerProvisioner;
import com.multiregion.queue.port.QueueRegionStateStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalQueueListenerCoordinatorTest {

    private final QueueRegionStateStore stateStore = mock(QueueRegionStateStore.class);
    private final QueueCoordinationPolicy policy = mock(QueueCoordinationPolicy.class);
    private final RecordingListenerProvisioner listenerProvisioner = new RecordingListenerProvisioner();

    @Test
    void stopsLocalPrimaryListenerWhenLocalQueueEntersDrDownState() {
        when(policy.names()).thenReturn(List.of("orders"));
        when(stateStore.findAll())
                .thenReturn(upInBothRegions())
                .thenReturn(List.of(
                        QueueRegionState.down("orders", "us-east-1"),
                        QueueRegionState.up("orders", "eu-west-1")
                ));
        LocalQueueListenerCoordinator coordinator = coordinator();

        coordinator.startLocalListeners();
        coordinator.reconcile();

        assertThat(coordinator.runningAssignments()).isEmpty();
        assertThat(listenerProvisioner.created).hasSize(1);
        assertThat(listenerProvisioner.created.get(0).stopped).isTrue();
    }

    @Test
    void restartsLocalPrimaryListenerWhenLocalQueueRecovers() {
        when(policy.names()).thenReturn(List.of("orders"));
        when(stateStore.findAll())
                .thenReturn(upInBothRegions())
                .thenReturn(List.of(
                        QueueRegionState.down("orders", "us-east-1"),
                        QueueRegionState.up("orders", "eu-west-1")
                ))
                .thenReturn(upInBothRegions());
        LocalQueueListenerCoordinator coordinator = coordinator();

        coordinator.startLocalListeners();
        coordinator.reconcile();
        coordinator.reconcile();

        assertThat(coordinator.runningAssignments()).containsExactly(
                new QueueListenerAssignment("orders", "us-east-1", ListenerMode.PRIMARY)
        );
        assertThat(listenerProvisioner.created).hasSize(2);
        assertThat(listenerProvisioner.created.get(1).running).isTrue();
    }

    private LocalQueueListenerCoordinator coordinator() {
        return new LocalQueueListenerCoordinator("us-east-1", policy, listenerProvisioner, stateStore);
    }

    private List<QueueRegionState> upInBothRegions() {
        return List.of(
                QueueRegionState.up("orders", "us-east-1"),
                QueueRegionState.up("orders", "eu-west-1")
        );
    }

    private static class RecordingListenerProvisioner implements QueueListenerProvisioner {
        private final List<RecordingContainer> created = new ArrayList<>();

        @Override
        public QueueListenerContainer create(QueueListenerAssignment assignment) {
            RecordingContainer container = new RecordingContainer(assignment);
            created.add(container);
            return container;
        }
    }

    private static class RecordingContainer implements QueueListenerContainer {
        private final QueueListenerAssignment assignment;
        private boolean running;
        private boolean stopped;

        private RecordingContainer(QueueListenerAssignment assignment) {
            this.assignment = assignment;
        }

        @Override
        public QueueListenerAssignment assignment() {
            return assignment;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
            stopped = true;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}

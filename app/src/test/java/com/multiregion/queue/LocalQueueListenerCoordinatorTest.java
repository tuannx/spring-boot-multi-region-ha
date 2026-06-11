package com.multiregion.queue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalQueueListenerCoordinatorTest {

    private final QueueRegionStateRepository repository = mock(QueueRegionStateRepository.class);
    private final QueueCoordinationProperties properties = new QueueCoordinationProperties();
    private final RecordingContainerFactory containerFactory = new RecordingContainerFactory();

    @Test
    void stopsLocalPrimaryListenerWhenLocalQueueEntersDrDownState() {
        properties.setNames(List.of("orders"));
        when(repository.findAll())
                .thenReturn(List.of(
                        QueueRegionState.up("orders", "us-east-1"),
                        QueueRegionState.up("orders", "eu-west-1")
                ))
                .thenReturn(List.of(
                        QueueRegionState.down("orders", "us-east-1"),
                        QueueRegionState.up("orders", "eu-west-1")
                ));
        LocalQueueListenerCoordinator coordinator = new LocalQueueListenerCoordinator(
                "us-east-1",
                properties,
                containerFactory,
                repository
        );

        coordinator.startLocalListeners();
        coordinator.reconcileLocalListeners();

        assertThat(coordinator.runningAssignments()).isEmpty();
        assertThat(containerFactory.created).hasSize(1);
        assertThat(containerFactory.created.get(0).stopped).isTrue();
    }

    @Test
    void restartsLocalPrimaryListenerWhenLocalQueueRecovers() {
        properties.setNames(List.of("orders"));
        when(repository.findAll())
                .thenReturn(List.of(
                        QueueRegionState.up("orders", "us-east-1"),
                        QueueRegionState.up("orders", "eu-west-1")
                ))
                .thenReturn(List.of(
                        QueueRegionState.down("orders", "us-east-1"),
                        QueueRegionState.up("orders", "eu-west-1")
                ))
                .thenReturn(List.of(
                        QueueRegionState.up("orders", "us-east-1"),
                        QueueRegionState.up("orders", "eu-west-1")
                ));
        LocalQueueListenerCoordinator coordinator = new LocalQueueListenerCoordinator(
                "us-east-1",
                properties,
                containerFactory,
                repository
        );

        coordinator.startLocalListeners();
        coordinator.reconcileLocalListeners();
        coordinator.reconcileLocalListeners();

        assertThat(coordinator.runningAssignments()).containsExactly(
                new QueueListenerAssignment("orders", "us-east-1", ListenerMode.PRIMARY)
        );
        assertThat(containerFactory.created).hasSize(2);
        assertThat(containerFactory.created.get(1).running).isTrue();
    }

    private static class RecordingContainerFactory implements QueueListenerContainerFactory {
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

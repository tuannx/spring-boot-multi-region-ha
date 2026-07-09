package com.multiregion.queue.application;

import com.multiregion.queue.domain.ListenerMode;
import com.multiregion.queue.domain.QueueListenerAssignment;
import com.multiregion.queue.domain.QueueRegionState;
import com.multiregion.queue.port.QueueCoordinationPolicy;
import com.multiregion.queue.port.QueueListenerContainer;
import com.multiregion.queue.port.QueueListenerProvisioner;
import com.multiregion.queue.port.QueueRegionStateStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicQueueListenerCoordinatorTest {

    @Test
    void releasesExpiredTakeoverWithoutRestartingWhileFailureRemainsActive() {
        QueueRegionStateStore stateStore = mock(QueueRegionStateStore.class);
        QueueCoordinationPolicy policy = mock(QueueCoordinationPolicy.class);
        RecordingListenerProvisioner listenerProvisioner = new RecordingListenerProvisioner();
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        when(policy.takeoverMaxDurationMs()).thenReturn(1_000L);
        when(stateStore.findAll()).thenReturn(List.of(
                QueueRegionState.up("orders", "us-east-1"),
                QueueRegionState.down("orders", "eu-west-1")
        ));
        DynamicQueueListenerCoordinator coordinator = new DynamicQueueListenerCoordinator(
                "us-east-1", stateStore, listenerProvisioner, policy, clock);

        coordinator.reconcile();
        clock.advanceMillis(1_000);
        coordinator.reconcile();

        assertThat(coordinator.runningAssignments()).isEmpty();
        assertThat(listenerProvisioner.created).hasSize(1);
        assertThat(listenerProvisioner.created.get(0).stopped).isTrue();
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

    private static class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

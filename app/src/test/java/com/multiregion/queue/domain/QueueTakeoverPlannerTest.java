package com.multiregion.queue.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueueTakeoverPlannerTest {

    private final QueueTakeoverPlanner planner = new QueueTakeoverPlanner();

    @Test
    void doesNotCreateDynamicAssignmentWhenBothRegionsAreUp() {
        List<QueueRegionState> states = List.of(
                QueueRegionState.up("orders", "us-east-1"),
                QueueRegionState.up("orders", "eu-west-1")
        );

        List<QueueListenerAssignment> assignments = planner.plan("us-east-1", states);

        assertThat(assignments).isEmpty();
    }

    @Test
    void takesOverBrotherQueueWhenBrotherRegionIsDown() {
        List<QueueRegionState> states = List.of(
                QueueRegionState.up("orders", "us-east-1"),
                QueueRegionState.down("orders", "eu-west-1")
        );

        List<QueueListenerAssignment> assignments = planner.plan("us-east-1", states);

        assertThat(assignments).containsExactly(
                new QueueListenerAssignment("orders", "eu-west-1", ListenerMode.TAKEOVER)
        );
    }

    @Test
    void stopsAllDynamicTakeoversWhenLocalQueueIsDown() {
        List<QueueRegionState> states = List.of(
                QueueRegionState.down("orders", "us-east-1"),
                QueueRegionState.down("orders", "eu-west-1")
        );

        List<QueueListenerAssignment> assignments = planner.plan("us-east-1", states);

        assertThat(assignments).isEmpty();
    }

    @Test
    void supportsMultipleQueuesIndependently() {
        List<QueueRegionState> states = List.of(
                QueueRegionState.up("orders", "us-east-1"),
                QueueRegionState.down("orders", "eu-west-1"),
                QueueRegionState.up("billing", "us-east-1"),
                QueueRegionState.up("billing", "eu-west-1"),
                QueueRegionState.up("shipments", "us-east-1"),
                QueueRegionState.down("shipments", "eu-west-1")
        );

        List<QueueListenerAssignment> assignments = planner.plan("us-east-1", states);

        assertThat(assignments).containsExactly(
                new QueueListenerAssignment("orders", "eu-west-1", ListenerMode.TAKEOVER),
                new QueueListenerAssignment("shipments", "eu-west-1", ListenerMode.TAKEOVER)
        );
    }
}

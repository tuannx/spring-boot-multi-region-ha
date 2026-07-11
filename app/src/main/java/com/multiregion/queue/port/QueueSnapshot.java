package com.multiregion.queue.port;

import com.multiregion.queue.domain.QueueListenerAssignment;
import com.multiregion.queue.domain.QueueRegionState;

import java.util.List;

public record QueueSnapshot(
        List<QueueRegionState> states,
        List<QueueListenerAssignment> localAssignments,
        List<QueueListenerAssignment> takeoverAssignments,
        List<QueueListenerAssignment> runningAssignments
) {

    public QueueSnapshot {
        states = List.copyOf(states);
        localAssignments = List.copyOf(localAssignments);
        takeoverAssignments = List.copyOf(takeoverAssignments);
        runningAssignments = List.copyOf(runningAssignments);
    }
}

package com.multiregion.queue.port;

import com.multiregion.queue.domain.ListenerMode;
import com.multiregion.queue.domain.QueueListenerAssignment;

import java.util.List;

public interface QueueAssignmentCoordinator {

    ListenerMode mode();

    void reconcile();

    List<QueueListenerAssignment> runningAssignments();
}

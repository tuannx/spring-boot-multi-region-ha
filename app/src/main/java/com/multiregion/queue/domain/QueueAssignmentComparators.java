package com.multiregion.queue.domain;

import java.util.Comparator;

public final class QueueAssignmentComparators {

    public static final Comparator<QueueListenerAssignment> BY_QUEUE_OWNER_MODE = Comparator
            .comparing(QueueListenerAssignment::queueName)
            .thenComparing(QueueListenerAssignment::ownerRegion)
            .thenComparing(QueueListenerAssignment::mode);

    private QueueAssignmentComparators() {
    }
}

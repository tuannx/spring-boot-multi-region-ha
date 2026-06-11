package com.multiregion.queue;

import java.util.Comparator;

final class QueueAssignmentComparators {

    static final Comparator<QueueListenerAssignment> BY_QUEUE_OWNER_MODE = Comparator
            .comparing(QueueListenerAssignment::queueName)
            .thenComparing(QueueListenerAssignment::ownerRegion)
            .thenComparing(QueueListenerAssignment::mode);

    private QueueAssignmentComparators() {
    }
}

package com.multiregion.queue.logging;

import com.multiregion.queue.domain.QueueListenerAssignment;
import com.multiregion.queue.port.QueueListenerContainer;
import com.multiregion.queue.port.QueueListenerProvisioner;

public class LoggingQueueListenerProvisioner implements QueueListenerProvisioner {

    @Override
    public QueueListenerContainer create(QueueListenerAssignment assignment) {
        return new LoggingQueueListenerContainer(assignment);
    }
}

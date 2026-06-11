package com.multiregion.queue;

public class LoggingQueueListenerContainerFactory implements QueueListenerContainerFactory {

    @Override
    public QueueListenerContainer create(QueueListenerAssignment assignment) {
        return new LoggingQueueListenerContainer(assignment);
    }
}

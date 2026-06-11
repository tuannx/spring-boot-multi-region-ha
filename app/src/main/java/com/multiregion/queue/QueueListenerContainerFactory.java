package com.multiregion.queue;

public interface QueueListenerContainerFactory {

    QueueListenerContainer create(QueueListenerAssignment assignment);
}

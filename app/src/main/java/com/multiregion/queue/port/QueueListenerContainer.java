package com.multiregion.queue.port;

import com.multiregion.queue.domain.QueueListenerAssignment;

public interface QueueListenerContainer {

    QueueListenerAssignment assignment();

    void start();

    void stop();

    boolean isRunning();
}

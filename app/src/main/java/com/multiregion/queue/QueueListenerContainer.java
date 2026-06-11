package com.multiregion.queue;

public interface QueueListenerContainer {

    QueueListenerAssignment assignment();

    void start();

    void stop();

    boolean isRunning();
}

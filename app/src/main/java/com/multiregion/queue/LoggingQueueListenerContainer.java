package com.multiregion.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingQueueListenerContainer implements QueueListenerContainer {

    private static final Logger log = LoggerFactory.getLogger(LoggingQueueListenerContainer.class);

    private final QueueListenerAssignment assignment;
    private boolean running;

    public LoggingQueueListenerContainer(QueueListenerAssignment assignment) {
        this.assignment = assignment;
    }

    @Override
    public QueueListenerAssignment assignment() {
        return assignment;
    }

    @Override
    public void start() {
        if (!running) {
            running = true;
            log.info("Queue listener started: queue={} ownerRegion={} mode={}",
                    assignment.queueName(), assignment.ownerRegion(), assignment.mode());
        }
    }

    @Override
    public void stop() {
        if (running) {
            running = false;
            log.info("Queue listener stopped: queue={} ownerRegion={} mode={}",
                    assignment.queueName(), assignment.ownerRegion(), assignment.mode());
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}

package com.multiregion.queue.rabbitmq;

import com.multiregion.queue.domain.QueueListenerAssignment;
import com.multiregion.queue.port.QueueListenerContainer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

public class RabbitMqQueueListenerContainer implements QueueListenerContainer, MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqQueueListenerContainer.class);

    private final QueueListenerAssignment assignment;
    private final String physicalQueueName;
    private final CachingConnectionFactory connectionFactory;
    private final SimpleMessageListenerContainer delegate;

    public RabbitMqQueueListenerContainer(
            QueueListenerAssignment assignment,
            String physicalQueueName,
            CachingConnectionFactory connectionFactory,
            RabbitMqQueueProperties properties) {
        this.assignment = assignment;
        this.physicalQueueName = physicalQueueName;
        this.connectionFactory = connectionFactory;
        this.delegate = new SimpleMessageListenerContainer(connectionFactory);
        this.delegate.setQueueNames(physicalQueueName);
        this.delegate.setMessageListener(this);
        this.delegate.setAcknowledgeMode(AcknowledgeMode.AUTO);
        this.delegate.setDefaultRequeueRejected(false);
        this.delegate.setMissingQueuesFatal(false);
        this.delegate.setAutoStartup(false);
        this.delegate.setPrefetchCount(properties.prefetchCount());
        this.delegate.setRecoveryInterval(properties.recoveryIntervalMs());
        this.delegate.setReceiveTimeout(properties.visibilityTimeoutMs());
    }

    @Override
    public QueueListenerAssignment assignment() {
        return assignment;
    }

    @Override
    public void start() {
        delegate.start();
        log.info("RabbitMQ listener started: logicalQueue={} physicalQueue={} ownerRegion={} mode={}",
                assignment.queueName(), physicalQueueName, assignment.ownerRegion(), assignment.mode());
    }

    @Override
    public void stop() {
        delegate.stop();
        connectionFactory.destroy();
        log.info("RabbitMQ listener stopped: logicalQueue={} physicalQueue={} ownerRegion={} mode={}",
                assignment.queueName(), physicalQueueName, assignment.ownerRegion(), assignment.mode());
    }

    @Override
    public boolean isRunning() {
        return delegate.isRunning();
    }

    @Override
    public void onMessage(Message message) {
        log.info("RabbitMQ message consumed: logicalQueue={} physicalQueue={} ownerRegion={} mode={} body={}",
                assignment.queueName(),
                physicalQueueName,
                assignment.ownerRegion(),
                assignment.mode(),
                new String(message.getBody()));
    }
}

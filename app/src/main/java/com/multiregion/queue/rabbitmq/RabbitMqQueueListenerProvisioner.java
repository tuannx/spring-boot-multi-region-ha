package com.multiregion.queue.rabbitmq;

import com.multiregion.queue.domain.QueueListenerAssignment;
import com.multiregion.queue.port.QueueListenerContainer;
import com.multiregion.queue.port.QueueListenerProvisioner;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

public class RabbitMqQueueListenerProvisioner implements QueueListenerProvisioner {

    private final RabbitMqQueueProperties properties;

    public RabbitMqQueueListenerProvisioner(RabbitMqQueueProperties properties) {
        this.properties = properties;
    }

    @Override
    public QueueListenerContainer create(QueueListenerAssignment assignment) {
        RabbitMqRegionBrokerProperties broker = properties.brokers().get(assignment.ownerRegion());
        if (broker == null) {
            throw new IllegalArgumentException("No RabbitMQ broker configured for region: " + assignment.ownerRegion());
        }

        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(broker.host(), broker.port());
        connectionFactory.setUsername(properties.username());
        connectionFactory.setPassword(properties.password());

        String physicalQueueName = properties.queueName(assignment.queueName(), assignment.ownerRegion());
        return new RabbitMqQueueListenerContainer(assignment, physicalQueueName, connectionFactory, properties);
    }
}

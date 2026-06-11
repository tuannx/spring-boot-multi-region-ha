package com.multiregion.queue;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

public class RabbitMqQueueListenerContainerFactory implements QueueListenerContainerFactory {

    private final RabbitMqQueueProperties properties;

    public RabbitMqQueueListenerContainerFactory(RabbitMqQueueProperties properties) {
        this.properties = properties;
    }

    @Override
    public QueueListenerContainer create(QueueListenerAssignment assignment) {
        RabbitMqRegionBrokerProperties broker = properties.getBrokers().get(assignment.ownerRegion());
        if (broker == null) {
            throw new IllegalArgumentException("No RabbitMQ broker configured for region: " + assignment.ownerRegion());
        }

        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(broker.getHost(), broker.getPort());
        connectionFactory.setUsername(properties.getUsername());
        connectionFactory.setPassword(properties.getPassword());

        String physicalQueueName = properties.queueName(assignment.queueName(), assignment.ownerRegion());
        return new RabbitMqQueueListenerContainer(assignment, physicalQueueName, connectionFactory, properties);
    }
}

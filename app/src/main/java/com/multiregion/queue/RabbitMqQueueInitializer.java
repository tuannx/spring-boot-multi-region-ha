package com.multiregion.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
@ConditionalOnProperty(prefix = "queues", name = "listener-type", havingValue = "rabbit")
public class RabbitMqQueueInitializer {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqQueueInitializer.class);

    private final QueueCoordinationProperties queueProperties;
    private final RabbitMqQueueProperties rabbitProperties;

    public RabbitMqQueueInitializer(
            QueueCoordinationProperties queueProperties,
            RabbitMqQueueProperties rabbitProperties) {
        this.queueProperties = queueProperties;
        this.rabbitProperties = rabbitProperties;
    }

    @PostConstruct
    public void declareQueues() {
        for (String region : queueProperties.getRegions()) {
            RabbitMqRegionBrokerProperties broker = rabbitProperties.getBrokers().get(region);
            if (broker == null) {
                log.warn("No RabbitMQ broker configured for region={}", region);
                continue;
            }

            CachingConnectionFactory connectionFactory = connectionFactory(broker);
            RabbitAdmin admin = new RabbitAdmin(connectionFactory);
            for (String queueName : queueProperties.getNames()) {
                String physicalQueueName = rabbitProperties.queueName(queueName, region);
                String retryQueueName = rabbitProperties.retryQueueName(queueName, region);
                String dlqName = rabbitProperties.dlqName(queueName, region);

                if (rabbitProperties.isRecreateTopology()) {
                    admin.deleteQueue(physicalQueueName);
                    admin.deleteQueue(retryQueueName);
                    admin.deleteQueue(dlqName);
                }

                admin.declareQueue(QueueBuilder.durable(dlqName).build());
                admin.declareQueue(QueueBuilder.durable(retryQueueName)
                        .ttl((int) rabbitProperties.getRetryDelayMs())
                        .deadLetterExchange("")
                        .deadLetterRoutingKey(physicalQueueName)
                        .build());
                admin.declareQueue(QueueBuilder.durable(physicalQueueName)
                        .deadLetterExchange("")
                        .deadLetterRoutingKey(retryQueueName)
                        .build());
                log.info("Declared RabbitMQ queue set: region={} main={} retry={} dlq={} retryDelayMs={}",
                        region,
                        physicalQueueName,
                        retryQueueName,
                        dlqName,
                        rabbitProperties.getRetryDelayMs());
            }
            connectionFactory.destroy();
        }
    }

    private CachingConnectionFactory connectionFactory(RabbitMqRegionBrokerProperties broker) {
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(broker.getHost(), broker.getPort());
        connectionFactory.setUsername(rabbitProperties.getUsername());
        connectionFactory.setPassword(rabbitProperties.getPassword());
        return connectionFactory;
    }
}

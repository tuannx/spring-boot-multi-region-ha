package com.multiregion.queue;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QueueListenerConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "queues", name = "listener-type", havingValue = "rabbit")
    public QueueListenerContainerFactory rabbitQueueListenerContainerFactory(
            RabbitMqQueueProperties properties) {
        return new RabbitMqQueueListenerContainerFactory(properties);
    }

    @Bean
    @ConditionalOnMissingBean(QueueListenerContainerFactory.class)
    public QueueListenerContainerFactory queueListenerContainerFactory() {
        return new LoggingQueueListenerContainerFactory();
    }
}

package com.multiregion.queue.config;

import com.multiregion.queue.application.DynamicQueueListenerCoordinator;
import com.multiregion.queue.application.LocalQueueListenerCoordinator;
import com.multiregion.queue.application.QueueManagementService;
import com.multiregion.queue.logging.LoggingQueueListenerProvisioner;
import com.multiregion.queue.port.QueueAssignmentCoordinator;
import com.multiregion.queue.port.QueueListenerProvisioner;
import com.multiregion.queue.port.QueueManagementUseCase;
import com.multiregion.queue.port.QueueRegionStateStore;
import com.multiregion.queue.rabbitmq.RabbitMqQueueListenerProvisioner;
import com.multiregion.queue.rabbitmq.RabbitMqQueueProperties;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

@Configuration
public class QueueListenerConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "queues", name = "listener-type", havingValue = "rabbit")
    public QueueListenerProvisioner rabbitQueueListenerProvisioner(
            RabbitMqQueueProperties properties) {
        return new RabbitMqQueueListenerProvisioner(properties);
    }

    @Bean
    @ConditionalOnMissingBean(QueueListenerProvisioner.class)
    public QueueListenerProvisioner queueListenerProvisioner() {
        return new LoggingQueueListenerProvisioner();
    }

    @Bean
    public Clock queueClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(prefix = "queues", name = "enabled", havingValue = "true", matchIfMissing = true)
    public LocalQueueListenerCoordinator localQueueListenerCoordinator(
            @Value("${AWS_REGION:us-east-1}") String localRegion,
            QueueCoordinationProperties properties,
            QueueListenerProvisioner listenerProvisioner,
            QueueRegionStateStore stateStore) {
        return new LocalQueueListenerCoordinator(localRegion, properties, listenerProvisioner, stateStore);
    }

    @Bean
    @ConditionalOnProperty(prefix = "queues", name = "enabled", havingValue = "true", matchIfMissing = true)
    public DynamicQueueListenerCoordinator dynamicQueueListenerCoordinator(
            @Value("${AWS_REGION:us-east-1}") String localRegion,
            QueueRegionStateStore stateStore,
            QueueListenerProvisioner listenerProvisioner,
            QueueCoordinationProperties properties,
            Clock clock) {
        return new DynamicQueueListenerCoordinator(localRegion, stateStore, listenerProvisioner, properties, clock);
    }

    @Bean
    public QueueManagementUseCase queueManagementUseCase(
            QueueRegionStateStore stateStore,
            List<QueueAssignmentCoordinator> coordinators) {
        return new QueueManagementService(stateStore, coordinators);
    }
}

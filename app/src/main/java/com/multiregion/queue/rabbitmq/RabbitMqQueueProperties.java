package com.multiregion.queue.rabbitmq;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

@ConfigurationProperties(prefix = "queues.rabbitmq")
public record RabbitMqQueueProperties(
        @DefaultValue("appuser") String username,
        @DefaultValue("apppass") String password,
        @DefaultValue("%s.%s") String queueNamePattern,
        @DefaultValue(".retry") String retryQueueSuffix,
        @DefaultValue(".dlq") String dlqSuffix,
        @DefaultValue("5000") long retryDelayMs,
        @DefaultValue("30000") long visibilityTimeoutMs,
        @DefaultValue("5000") long recoveryIntervalMs,
        @DefaultValue("1") int prefetchCount,
        @DefaultValue("true") boolean recreateTopology,
        Map<String, RabbitMqRegionBrokerProperties> brokers
) {

    public RabbitMqQueueProperties {
        brokers = brokers == null ? Map.of() : Map.copyOf(brokers);
    }

    public String queueName(String logicalQueueName, String region) {
        return String.format(queueNamePattern, logicalQueueName, region);
    }

    public String retryQueueName(String logicalQueueName, String region) {
        return queueName(logicalQueueName, region) + retryQueueSuffix;
    }

    public String dlqName(String logicalQueueName, String region) {
        return queueName(logicalQueueName, region) + dlqSuffix;
    }
}

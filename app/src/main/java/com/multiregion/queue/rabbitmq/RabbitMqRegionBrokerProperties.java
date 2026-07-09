package com.multiregion.queue.rabbitmq;

import org.springframework.boot.context.properties.bind.DefaultValue;

public record RabbitMqRegionBrokerProperties(
        @DefaultValue("localhost") String host,
        @DefaultValue("5672") int port
) {
}

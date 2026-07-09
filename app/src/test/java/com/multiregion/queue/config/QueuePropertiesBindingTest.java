package com.multiregion.queue.config;

import com.multiregion.queue.rabbitmq.RabbitMqQueueProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class QueuePropertiesBindingTest {

    @Test
    void bindsImmutableCoordinationAndRabbitMqConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("queues.names[0]", "orders")
                .withProperty("queues.names[1]", "billing")
                .withProperty("queues.takeover-max-duration-ms", "900000")
                .withProperty("queues.rabbitmq.brokers.us-east-1.host", "rabbitmq-us")
                .withProperty("queues.rabbitmq.brokers.us-east-1.port", "5673");
        Binder binder = Binder.get(environment);

        QueueCoordinationProperties queues = binder.bind(
                "queues", Bindable.of(QueueCoordinationProperties.class)).get();
        RabbitMqQueueProperties rabbit = binder.bind(
                "queues.rabbitmq", Bindable.of(RabbitMqQueueProperties.class)).get();

        assertThat(queues.names()).containsExactly("orders", "billing");
        assertThat(queues.takeoverMaxDurationMs()).isEqualTo(900_000L);
        assertThat(rabbit.brokers().get("us-east-1").host()).isEqualTo("rabbitmq-us");
        assertThat(rabbit.brokers().get("us-east-1").port()).isEqualTo(5673);
        assertThat(rabbit.prefetchCount()).isEqualTo(1);
    }
}

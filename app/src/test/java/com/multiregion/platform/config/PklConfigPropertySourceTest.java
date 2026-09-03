package com.multiregion.platform.config;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pkl.spring.boot.PklPropertySourceLoader;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class PklConfigPropertySourceTest {

    @Test
    void evaluatesTheTypedFailoverSchemaAsSpringProperties() throws IOException {
        var resource = new DefaultResourceLoader().getResource("classpath:/pkl/application.pkl");
        var propertySource = new PklPropertySourceLoader().load("pkl", resource).getFirst();

        assertThat(propertySource.getProperty("REGION_ROLE")).isEqualTo("primary");
        assertThat(propertySource.getProperty("AWS_REGION")).isEqualTo("us-east-1");
        assertThat(propertySource.getProperty("FAILOVER_HOME_REGION")).isEqualTo("us-east-1");
        assertThat(propertySource.getProperty("DB_NAME")).isEqualTo("appdb");
        assertThat(String.valueOf(propertySource.getProperty("FAILOVER_FAILURE_THRESHOLD")))
                .isEqualTo("3");
        assertThat(propertySource.getProperty("FAILOVER_ALLOW_UNFENCED_PROMOTION"))
                .isEqualTo(false);
        assertThat(String.valueOf(propertySource.getProperty("server.port")))
                .isEqualTo("8080");
        assertThat(propertySource.getProperty("spring.application.name"))
                .isEqualTo("multiregion-app");
        assertThat(propertySource.getProperty("spring.threads.virtual.enabled"))
                .isEqualTo(true);
        assertThat(propertySource.getProperty("spring.jpa.hibernate.ddlAuto"))
                .isEqualTo("update");
        assertThat(propertySource.getProperty("spring.jpa.properties.hibernate.dialect"))
                .isEqualTo("org.hibernate.dialect.PostgreSQLDialect");
        assertThat(propertySource.getProperty("management.endpoint.health.showDetails"))
                .isEqualTo("always");
        assertThat(propertySource.getProperty("queues.listenerType"))
                .isEqualTo("logging");
        assertThat(propertySource.getProperty("queues.rabbitmq.brokers.us-east-1.host"))
                .isEqualTo("localhost");
    }

    @Test
    void bindsGeneratedConfigurationWithSpringEnvironmentOverrides() throws IOException {
        var resource = new DefaultResourceLoader().getResource("classpath:/pkl/application.pkl");
        var propertySource = new PklPropertySourceLoader().load("pkl", resource).getFirst();
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "test-system-environment",
                Map.of(
                        "SERVER_PORT", "18082",
                        "QUEUES_LISTENERTYPE", "rabbit",
                        "AWS_REGION", "eu-west-1")));
        environment.getPropertySources().addLast(propertySource);

        PklApplicationConfig config = Binder.get(environment)
                .bind("", Bindable.of(PklApplicationConfig.class))
                .orElseThrow(() -> new AssertionError("Pkl configuration did not bind"));

        assertThat(config.getServer().getPort()).isEqualTo(18082);
        assertThat(config.getAWS_REGION()).isEqualTo("eu-west-1");
        assertThat(environment.getProperty("queues.listenerType")).isEqualTo("rabbit");
        assertThat(config.getQueues().getListenerType()).isEqualTo("logging");
        assertThat(config.getQueues().getRabbitmq().getBrokers())
                .containsKey("us-east-1");
    }

    @Test
    void profileModuleOverridesOnlyItsRegionalValues() throws IOException {
        var resource = new DefaultResourceLoader()
                .getResource("classpath:/pkl/application-region-eu.pkl");
        var propertySource = new PklPropertySourceLoader()
                .load("pkl", resource)
                .getFirst();

        assertThat(propertySource.getProperty("AWS_REGION")).isEqualTo("eu-west-1");
        assertThat(propertySource.getProperty("REGION_ROLE")).isEqualTo("secondary");
        assertThat(propertySource.getProperty("FAILOVER_HOME_REGION")).isEqualTo("us-east-1");
        assertThat(String.valueOf(propertySource.getProperty("server.port")))
                .isEqualTo("8080");
    }
}

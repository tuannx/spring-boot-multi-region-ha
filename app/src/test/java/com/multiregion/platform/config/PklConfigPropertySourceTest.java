package com.multiregion.platform.config;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.pkl.spring.boot.PklPropertySourceLoader;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class PklConfigPropertySourceTest {

    @Test
    void evaluatesTheTypedFailoverSchemaAsSpringProperties() throws IOException {
        var resource = new DefaultResourceLoader().getResource("classpath:/pkl/application.pkl");
        var propertySource = new PklPropertySourceLoader().load("pkl", resource).getFirst();

        assertThat(propertySource.getProperty("REGION_ROLE")).isEqualTo("primary");
        assertThat(propertySource.getProperty("AWS_REGION")).isEqualTo("us-east-1");
        assertThat(propertySource.getProperty("FAILOVER_HOME_REGION")).isEqualTo("us-east-1");
        assertThat(String.valueOf(propertySource.getProperty("FAILOVER_FAILURE_THRESHOLD")))
                .isEqualTo("3");
        assertThat(propertySource.getProperty("FAILOVER_ALLOW_UNFENCED_PROMOTION"))
                .isEqualTo(false);
    }
}

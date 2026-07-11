package com.multiregion.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import static org.assertj.core.api.Assertions.assertThat;

class MultiRegionConfigTest {

    @Test
    void bindsRegionValuesThroughConstructorInjection() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    context,
                    "REGION_ROLE=secondary",
                    "AWS_REGION=eu-west-1",
                    "FAILOVER_HOME_REGION=us-east-1");
            context.register(MultiRegionConfig.class);
            context.refresh();

            MultiRegionConfig config = context.getBean(MultiRegionConfig.class);
            assertThat(config.regionRole()).isEqualTo("secondary");
            assertThat(config.awsRegion()).isEqualTo("eu-west-1");
            assertThat(config.failoverHomeRegion()).isEqualTo("us-east-1");
            assertThat(config.isSecondary()).isTrue();
        }
    }
}

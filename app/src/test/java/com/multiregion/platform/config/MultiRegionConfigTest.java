package com.multiregion.platform.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MultiRegionConfigTest {

    @Test
    void exposesGeneratedPklValuesThroughTheApplicationAdapter() {
        MultiRegionConfig config = new MultiRegionConfig(new PklMultiRegionConfig(
                "secondary", "eu-west-1", "us-east-1", 5, false));

        assertThat(config.regionRole()).isEqualTo("secondary");
        assertThat(config.awsRegion()).isEqualTo("eu-west-1");
        assertThat(config.failoverHomeRegion()).isEqualTo("us-east-1");
        assertThat(config.failoverFailureThreshold()).isEqualTo(5);
        assertThat(config.allowUnfencedPromotion()).isFalse();
        assertThat(config.isSecondary()).isTrue();
    }
}

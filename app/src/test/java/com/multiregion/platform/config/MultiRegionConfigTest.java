package com.multiregion.platform.config;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MultiRegionConfigTest {

    @Test
    void exposesGeneratedPklValuesThroughTheApplicationAdapter() {
        MultiRegionConfig config = new MultiRegionConfig(new PklApplicationConfig(
                null,
                null,
                null,
                null,
                null,
                "appuser",
                "apppass",
                "appdb",
                "eu-west-1",
                "secondary",
                "us-east-1",
                "strict-writer",
                "home-reader-or-writer",
                "postgres-us:5432,postgres-eu:5432",
                "postgres-?:5432",
                "primary-db",
                6432,
                "eu-local-db",
                6543,
                "promoted-db",
                7432,
                5,
                false));

        assertThat(config.regionRole()).isEqualTo("secondary");
        assertThat(config.awsRegion()).isEqualTo("eu-west-1");
        assertThat(config.failoverHomeRegion()).isEqualTo("us-east-1");
        assertThat(config.dbName()).isEqualTo("appdb");
        assertThat(config.activeWriterDbHost()).isEqualTo("primary-db");
        assertThat(config.failoverWriterDbPort()).isEqualTo(7432);
        assertThat(config.failoverFailureThreshold()).isEqualTo(5);
        assertThat(config.allowUnfencedPromotion()).isFalse();
        assertThat(config.isSecondary()).isTrue();
    }
}

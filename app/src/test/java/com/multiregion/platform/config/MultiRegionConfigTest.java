package com.multiregion.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
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

    @Test
    void keepsDeploymentEnvironmentOverridesAbovePklDefaults() {
        PklApplicationConfig defaults = new PklApplicationConfig(
                null,
                null,
                null,
                null,
                null,
                "appuser",
                "apppass",
                "appdb",
                "us-east-1",
                "primary",
                "us-east-1",
                "strict-writer",
                "home-reader-or-writer",
                "postgres-us:5432,postgres-eu:5432",
                "postgres-?:5432",
                "postgres-us",
                5432,
                "postgres-us",
                5432,
                "postgres-eu",
                5432,
                3,
                false);
        MultiRegionConfig config = new MultiRegionConfig(
                defaults,
                new MockEnvironment()
                        .withProperty("DB_PASS", "runtime-pass")
                        .withProperty("AWS_REGION", "eu-west-1")
                        .withProperty("ACTIVE_WRITER_DB_HOST", "writer.example")
                        .withProperty("ACTIVE_WRITER_DB_PORT", "6432")
                        .withProperty("FAILOVER_ALLOW_UNFENCED_PROMOTION", "true"));

        assertThat(config.dbPass()).isEqualTo("runtime-pass");
        assertThat(config.awsRegion()).isEqualTo("eu-west-1");
        assertThat(config.activeWriterDbHost()).isEqualTo("writer.example");
        assertThat(config.activeWriterDbPort()).isEqualTo(6432);
        assertThat(config.allowUnfencedPromotion()).isTrue();
    }
}

package com.multiregion.platform.database;

import com.multiregion.platform.config.MultiRegionConfig;
import com.multiregion.platform.config.PklApplicationConfig;
import com.multiregion.platform.routing.RoutingDataSource;
import com.multiregion.platform.routing.RoutingDataSources;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DatabaseConnectionsTest {

    @Test
    void secondaryLocalAdminPoolTargetsConfiguredLocalDatabaseThroughWrapperWithoutPlugins() {
        DatabaseConnections config = config("eu-west-1", "secondary", "eu-local-db", 6543);

        try (HikariDataSource dataSource = (HikariDataSource) config.localAdminDataSource()) {
            assertAdvancedJdbcPool(dataSource);
            assertThat(dataSource.getJdbcUrl())
                    .isEqualTo("jdbc:aws-wrapper:postgresql://eu-local-db:6543/appdb");
            assertThat(dataSource.getDataSourceProperties())
                    .containsEntry("wrapperPlugins", "");
            assertThat(dataSource.isReadOnly()).isFalse();
        }
    }

    @Test
    void regionalReadPoolTargetsConfiguredLocalDatabaseThroughAwsWrapper() {
        DatabaseConnections config = config("eu-west-1", "secondary", "eu-local-db", 6543);

        try (HikariDataSource dataSource = (HikariDataSource) config.readDataSource()) {
            assertAdvancedJdbcPool(dataSource);
            assertThat(dataSource.getJdbcUrl())
                    .isEqualTo("jdbc:aws-wrapper:postgresql://eu-local-db:6543/appdb");
            assertThat(dataSource.getDataSourceProperties())
                    .containsEntry("wrapperPlugins", "failover2,dev");
            assertThat(dataSource.isReadOnly()).isTrue();
        }
    }

    @Test
    void secondaryLocalAdminPoolFallsBackToLocalEuDatabaseThroughWrapper() {
        DatabaseConnections config = config("eu-west-1", "secondary", "  ", 5432);

        try (HikariDataSource dataSource = (HikariDataSource) config.localAdminDataSource()) {
            assertAdvancedJdbcPool(dataSource);
            assertThat(dataSource.getJdbcUrl())
                    .isEqualTo("jdbc:aws-wrapper:postgresql://postgres-eu:5432/appdb")
                    .doesNotContain("postgres-us");
            assertThat(dataSource.getDataSourceProperties())
                    .containsEntry("wrapperPlugins", "");
            assertThat(dataSource.isReadOnly()).isFalse();
        }
    }

    @Test
    void primaryProbePoolTargetsActiveWriterThroughWrapperWithBoundedTimeouts() {
        DatabaseConnections config = config("eu-west-1", "secondary", "eu-local-db", 6543);

        try (HikariDataSource dataSource = (HikariDataSource) config.primaryProbeDataSource()) {
            assertAdvancedJdbcPool(dataSource);
            assertThat(dataSource.getJdbcUrl())
                    .isEqualTo("jdbc:aws-wrapper:postgresql://primary-db:6432/appdb");
            assertThat(dataSource.getDataSourceProperties())
                    .containsEntry("wrapperPlugins", "")
                    .containsEntry("connectTimeout", "5")
                    .containsEntry("socketTimeout", "5");
            assertThat(dataSource.getConnectionTimeout()).isEqualTo(5000);
            assertThat(dataSource.getValidationTimeout()).isEqualTo(2000);
            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(2);
            assertThat(dataSource.getMinimumIdle()).isZero();
            assertThat(dataSource.isReadOnly()).isTrue();
        }
    }

    @Test
    void failoverAwareApplicationPoolAlsoHasBoundedDriverFailover() {
        DatabaseConnections config = config("eu-west-1", "secondary", "eu-local-db", 6543);

        try (HikariDataSource dataSource = (HikariDataSource) config.writeDataSource()) {
            assertAdvancedJdbcPool(dataSource);
            assertThat(dataSource.getDataSourceProperties())
                    .containsEntry("wrapperPlugins", "failover2,dev")
                    .containsEntry("wrapperDialect", "pg")
                    .containsEntry("failoverTimeoutMs", "5000")
                    .containsEntry("connectTimeout", "5")
                    .containsEntry("socketTimeout", "5");
        }
    }

    @Test
    void promotedWriterPoolTargetsConfiguredFailoverWriterThroughWrapper() {
        DatabaseConnections config = config("eu-west-1", "secondary", "eu-local-db", 6543);

        try (HikariDataSource dataSource = (HikariDataSource) config.promotedWriterDataSource()) {
            assertAdvancedJdbcPool(dataSource);
            assertThat(dataSource.getJdbcUrl())
                    .isEqualTo("jdbc:aws-wrapper:postgresql://promoted-db:7432/appdb");
            assertThat(dataSource.getDataSourceProperties())
                    .containsEntry("wrapperPlugins", "");
            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(10);
            assertThat(dataSource.getMinimumIdle()).isEqualTo(2);
            assertThat(dataSource.isReadOnly()).isFalse();
        }
    }

    @Test
    void everyPhysicalApplicationPoolUsesAdvancedJdbcWrapper() {
        DatabaseConnections config = config("eu-west-1", "secondary", "eu-local-db", 6543);

        try (HikariDataSource write = (HikariDataSource) config.writeDataSource();
                HikariDataSource read = (HikariDataSource) config.readDataSource();
                HikariDataSource probe = (HikariDataSource) config.primaryProbeDataSource();
                HikariDataSource admin = (HikariDataSource) config.localAdminDataSource();
                HikariDataSource promoted = (HikariDataSource) config.promotedWriterDataSource()) {
            assertThat(List.of(write, read, probe, admin, promoted))
                    .allSatisfy(DatabaseConnectionsTest::assertAdvancedJdbcPool);
        }
    }

    @Test
    void regionalRouterExposesAllTrafficTargetsBehindLazyPrimaryProxy() {
        DataSource writer = mock(DataSource.class);
        DataSource reader = mock(DataSource.class);
        DataSource promotedWriter = mock(DataSource.class);

        RoutingDataSources routingConfig = new RoutingDataSources();
        RoutingDataSource regional =
                routingConfig.regionalRoutingDataSource(writer, reader, promotedWriter);
        DataSource primary = routingConfig.routingDataSource(regional);

        assertThat(regional.getResolvedDataSources())
                .containsEntry("writer", writer)
                .containsEntry("reader", reader)
                .containsEntry("promoted-writer", promotedWriter);
        assertThat(regional.getResolvedDefaultDataSource()).isSameAs(writer);
        assertThat(primary).isInstanceOf(LazyConnectionDataSourceProxy.class);
    }

    @Test
    void drillEnvironmentSelectsAuroraDialectAndIsolatesPoolTopology() {
        MultiRegionConfig config = new MultiRegionConfig(
                new PklApplicationConfig(
                        null, null, null, null, null, null, null,
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
                        "postgres-us",
                        5432,
                        "postgres-eu",
                        5432,
                        "postgres-eu",
                        5432,
                        3,
                        false),
                new MockEnvironment()
                        .withProperty("WRAPPER_DIALECT", "aurora-pg")
                        .withProperty("READ_FAILOVER_MODE", "strict-reader")
                        .withProperty("JDBC_CLUSTER_ID_FROM_POOL", "true")
                        .withProperty("CLUSTER_INSTANCE_PATTERN", "?:5432"));
        DatabaseConnections connections = new DatabaseConnections(config);

        try (HikariDataSource writer = (HikariDataSource) connections.writeDataSource();
                HikariDataSource reader = (HikariDataSource) connections.readDataSource();
                HikariDataSource admin = (HikariDataSource) connections.localAdminDataSource()) {
            assertThat(writer.getDataSourceProperties())
                    .containsEntry("wrapperDialect", "aurora-pg")
                    .containsEntry("wrapperPlugins", "failover2,dev")
                    .containsEntry("clusterInstanceHostPattern", "?:5432")
                    .containsEntry("clusterId", "WritePool-eu-west-1")
                    .doesNotContainKey("failoverMode");
            assertThat(reader.getDataSourceProperties())
                    .containsEntry("failoverMode", "strict-reader")
                    .containsEntry("clusterId", "ReadPool-eu-west-1")
                    .containsEntry("wrapperDialect", "aurora-pg");
            assertThat(reader.isReadOnly()).isTrue();
            assertThat(admin.getDataSourceProperties())
                    .containsEntry("wrapperDialect", "pg")
                    .containsEntry("wrapperPlugins", "")
                    .containsEntry("clusterInstanceHostPattern", "?:5432")
                    .doesNotContainKey("clusterId")
                    .doesNotContainKey("failoverMode");
        }
    }

    private DatabaseConnections config(
            String awsRegion,
            String regionRole,
            String localDbHost,
            int localDbPort) {
        return new DatabaseConnections(
                "appuser",
                "apppass",
                awsRegion,
                regionRole,
                "primary-db",
                6432,
                localDbHost,
                localDbPort,
                "promoted-db",
                7432,
                "appdb");
    }

    private static void assertAdvancedJdbcPool(HikariDataSource dataSource) {
        assertThat(dataSource.getJdbcUrl())
                .startsWith("jdbc:aws-wrapper:postgresql://");
        assertThat(dataSource.getDriverClassName())
                .isEqualTo("software.amazon.jdbc.Driver");
        assertThat(dataSource.getDataSourceProperties())
                .containsEntry("wrapperDialect", "pg");
    }
}

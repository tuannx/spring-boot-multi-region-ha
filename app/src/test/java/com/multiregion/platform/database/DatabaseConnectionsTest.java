package com.multiregion.platform.database;

import com.multiregion.platform.routing.RoutingDataSource;
import com.multiregion.platform.routing.RoutingDataSources;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DatabaseConnectionsTest {

    @Test
    void secondaryLocalAdminPoolTargetsConfiguredLocalDatabaseWithoutWrapperOrReadOnlyMode() {
        DatabaseConnections config = config("eu-west-1", "secondary", "eu-local-db", 6543);

        try (HikariDataSource dataSource = (HikariDataSource) config.localAdminDataSource()) {
            assertThat(dataSource.getJdbcUrl())
                    .startsWith("jdbc:postgresql://eu-local-db:6543/appdb")
                    .doesNotContain("aws-wrapper");
            assertThat(dataSource.isReadOnly()).isFalse();
        }
    }

    @Test
    void secondaryLocalAdminPoolFallsBackToLocalEuDatabase() {
        DatabaseConnections config = config("eu-west-1", "secondary", "  ", 5432);

        try (HikariDataSource dataSource = (HikariDataSource) config.localAdminDataSource()) {
            assertThat(dataSource.getJdbcUrl())
                    .startsWith("jdbc:postgresql://postgres-eu:5432/appdb")
                    .doesNotContain("postgres-us")
                    .doesNotContain("aws-wrapper");
            assertThat(dataSource.isReadOnly()).isFalse();
        }
    }

    @Test
    void primaryProbePoolTargetsActiveWriterDirectlyWithBoundedTimeouts() {
        DatabaseConnections config = config("eu-west-1", "secondary", "eu-local-db", 6543);

        try (HikariDataSource dataSource = (HikariDataSource) config.primaryProbeDataSource()) {
            assertThat(dataSource.getJdbcUrl())
                    .startsWith("jdbc:postgresql://primary-db:6432/appdb")
                    .contains("connectTimeout=5")
                    .contains("socketTimeout=5")
                    .doesNotContain("aws-wrapper");
            assertThat(dataSource.getConnectionTimeout()).isEqualTo(5000);
            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(2);
            assertThat(dataSource.getMinimumIdle()).isZero();
            assertThat(dataSource.isReadOnly()).isTrue();
        }
    }

    @Test
    void failoverAwareApplicationPoolAlsoHasBoundedDriverFailover() {
        DatabaseConnections config = config("eu-west-1", "secondary", "eu-local-db", 6543);

        try (HikariDataSource dataSource = (HikariDataSource) config.writeDataSource()) {
            assertThat(dataSource.getDataSourceProperties())
                    .containsEntry("failoverTimeoutMs", "5000")
                    .containsEntry("connectTimeout", "5")
                    .containsEntry("socketTimeout", "5");
        }
    }

    @Test
    void promotedWriterPoolTargetsConfiguredFailoverWriterWithNormalWriteCapacity() {
        DatabaseConnections config = config("eu-west-1", "secondary", "eu-local-db", 6543);

        try (HikariDataSource dataSource = (HikariDataSource) config.promotedWriterDataSource()) {
            assertThat(dataSource.getJdbcUrl())
                    .startsWith("jdbc:postgresql://promoted-db:7432/appdb")
                    .doesNotContain("aws-wrapper");
            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(10);
            assertThat(dataSource.getMinimumIdle()).isEqualTo(2);
            assertThat(dataSource.isReadOnly()).isFalse();
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

    private DatabaseConnections config(
            String awsRegion,
            String regionRole,
            String localDbHost,
            int localDbPort) {
        DatabaseConnections config = new DatabaseConnections();
        ReflectionTestUtils.setField(config, "dbUser", "appuser");
        ReflectionTestUtils.setField(config, "dbPass", "apppass");
        ReflectionTestUtils.setField(config, "awsRegion", awsRegion);
        ReflectionTestUtils.setField(config, "regionRole", regionRole);
        ReflectionTestUtils.setField(config, "activeWriterDbHost", "primary-db");
        ReflectionTestUtils.setField(config, "activeWriterDbPort", 6432);
        ReflectionTestUtils.setField(config, "localDbHost", localDbHost);
        ReflectionTestUtils.setField(config, "localDbPort", localDbPort);
        ReflectionTestUtils.setField(config, "failoverWriterDbHost", "promoted-db");
        ReflectionTestUtils.setField(config, "failoverWriterDbPort", 7432);
        ReflectionTestUtils.setField(config, "dbName", "appdb");
        return config;
    }
}

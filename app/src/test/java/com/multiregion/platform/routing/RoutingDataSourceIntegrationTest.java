package com.multiregion.platform.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingDataSourceIntegrationTest {

    private DataSource routingDataSource;
    private RoutingDataSource regionalRoutingDataSource;
    private RoutingProductDataRoute dataRoute;

    @BeforeEach
    void setUp() {
        DataSource writer = dataSource("writer");
        DataSource reader = dataSource("reader");
        DataSource promotedWriter = dataSource("promoted-writer");
        initialize(writer, "writer");
        initialize(reader, "reader");
        initialize(promotedWriter, "promoted-writer");

        RoutingDataSources config = new RoutingDataSources();
        regionalRoutingDataSource = config.regionalRoutingDataSource(
                writer,
                reader,
                promotedWriter);
        routingDataSource = config.routingDataSource(regionalRoutingDataSource);
        dataRoute = new RoutingProductDataRoute();
    }

    @Test
    void defersConnectionUntilExplicitReaderRouteIsSelectedInsideTransaction() {
        TransactionTemplate transaction = transactionTemplate(false);

        String selectedPool = transaction.execute(status -> dataRoute.read(
                () -> new JdbcTemplate(routingDataSource)
                        .queryForObject("SELECT pool_name FROM routing_probe", String.class)));

        assertThat(routingDataSource).isInstanceOf(LazyConnectionDataSourceProxy.class);
        assertThat(selectedPool).isEqualTo("reader");
    }

    @Test
    void explicitWriterRouteOverridesReadOnlyTransactionHint() {
        TransactionTemplate transaction = transactionTemplate(true);

        String selectedPool = transaction.execute(status -> dataRoute.write(
                () -> new JdbcTemplate(routingDataSource)
                        .queryForObject("SELECT pool_name FROM routing_probe", String.class)));

        assertThat(selectedPool).isEqualTo("writer");
    }

    @Test
    void explicitWriterRouteMovesToLocalPoolAfterPromotion() {
        regionalRoutingDataSource.activatePromotedWriter();
        TransactionTemplate transaction = transactionTemplate(true);

        String selectedPool = transaction.execute(status -> dataRoute.write(
                () -> new JdbcTemplate(routingDataSource)
                        .queryForObject("SELECT pool_name FROM routing_probe", String.class)));

        assertThat(selectedPool).isEqualTo("promoted-writer");
    }

    @Test
    void readerRouteRemainsRegionalAfterWriterPromotion() {
        regionalRoutingDataSource.activatePromotedWriter();
        TransactionTemplate transaction = transactionTemplate(false);

        String selectedPool = transaction.execute(status -> dataRoute.read(
                () -> new JdbcTemplate(routingDataSource)
                        .queryForObject("SELECT pool_name FROM routing_probe", String.class)));

        assertThat(selectedPool).isEqualTo("reader");
    }

    @Test
    void nestedRouteRestoresTheLexicallyScopedOuterRoute() {
        List<String> selectedPools = dataRoute.read(() -> List.of(
                selectedPool(),
                dataRoute.write(this::selectedPool),
                selectedPool()));

        assertThat(selectedPools).containsExactly("reader", "writer", "reader");
    }

    @Test
    void failedOperationDoesNotLeakItsRoute() {
        assertThatThrownBy(() -> dataRoute.read(() -> {
            throw new IllegalStateException("simulated failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(selectedPool()).isEqualTo("writer");
    }

    private TransactionTemplate transactionTemplate(boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(
                new DataSourceTransactionManager(routingDataSource));
        template.setReadOnly(readOnly);
        return template;
    }

    private DataSource dataSource(String name) {
        return new DriverManagerDataSource(
                "jdbc:h2:mem:routing_" + name + ";DB_CLOSE_DELAY=-1", "sa", "");
    }

    private String selectedPool() {
        return new JdbcTemplate(routingDataSource)
                .queryForObject("SELECT pool_name FROM routing_probe", String.class);
    }

    private void initialize(DataSource dataSource, String poolName) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS routing_probe");
        jdbc.execute("CREATE TABLE routing_probe (pool_name VARCHAR(16) NOT NULL)");
        jdbc.update("INSERT INTO routing_probe(pool_name) VALUES (?)", poolName);
    }
}

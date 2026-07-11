package com.multiregion.platform.failover.jdbc;

import com.multiregion.platform.failover.domain.PrimaryProbeResult;
import com.multiregion.platform.failover.domain.PrimaryProbeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcAuroraTopologyGatewayTest {

    @Test
    void connectionResourceFailureIsClassifiedAsUnreachable() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(
                new SQLException("connection refused", "08001"));
        JdbcAuroraTopologyGateway gateway = gateway(dataSource);

        PrimaryProbeResult result = gateway.probePrimary();

        assertThat(result.status()).isEqualTo(PrimaryProbeStatus.UNREACHABLE);
        assertThat(result.detail()).contains("JDBC Connection");
    }

    @Test
    void socketFailureInCauseChainIsClassifiedAsUnreachable() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        SQLException networkFailure = new SQLException("network failure");
        networkFailure.initCause(new SocketException("connection reset"));
        when(dataSource.getConnection()).thenThrow(networkFailure);
        JdbcAuroraTopologyGateway gateway = gateway(dataSource);

        PrimaryProbeResult result = gateway.probePrimary();

        assertThat(result.status()).isEqualTo(PrimaryProbeStatus.UNREACHABLE);
    }

    @Test
    void socketTimeoutInCauseChainIsClassifiedAsUnreachable() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        SQLException networkFailure = new SQLException("network read timed out");
        networkFailure.initCause(new SocketTimeoutException("read timed out"));
        when(dataSource.getConnection()).thenThrow(networkFailure);
        JdbcAuroraTopologyGateway gateway = gateway(dataSource);

        PrimaryProbeResult result = gateway.probePrimary();

        assertThat(result.status()).isEqualTo(PrimaryProbeStatus.UNREACHABLE);
    }

    @Test
    void queryTimeoutIsClassifiedAsFailedInsteadOfPrimaryOutage() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new QueryTimeoutException("query timed out"));
        JdbcAuroraTopologyGateway gateway = gateway(dataSource);

        PrimaryProbeResult result = gateway.probePrimary();

        assertThat(result.status()).isEqualTo(PrimaryProbeStatus.FAILED);
        assertThat(result.status()).isNotEqualTo(PrimaryProbeStatus.UNREACHABLE);
    }

    @Test
    void poolExhaustionWithoutConnectivitySqlStateIsClassifiedAsFailed() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(
                new SQLTransientConnectionException("pool exhausted"));
        JdbcAuroraTopologyGateway gateway = gateway(dataSource);

        PrimaryProbeResult result = gateway.probePrimary();

        assertThat(result.status()).isEqualTo(PrimaryProbeStatus.FAILED);
        assertThat(result.status()).isNotEqualTo(PrimaryProbeStatus.UNREACHABLE);
    }

    @Test
    void primaryTopologyQueryHasFiveSecondStatementTimeout() {
        DataSource dataSource = mock(DataSource.class);
        JdbcAuroraTopologyGateway gateway = gateway(dataSource);

        JdbcTemplate jdbcTemplate = (JdbcTemplate) ReflectionTestUtils.getField(
                gateway,
                "primaryProbeJdbcTemplate");

        assertThat(jdbcTemplate).isNotNull();
        assertThat(jdbcTemplate.getQueryTimeout()).isEqualTo(5);
    }

    @Test
    void reachableDatabaseWithInvalidAuroraTopologyQueryIsClassifiedAsFailed() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:invalid_aurora_topology;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        JdbcAuroraTopologyGateway gateway = gateway(dataSource);

        PrimaryProbeResult result = gateway.probePrimary();

        assertThat(result.status()).isEqualTo(PrimaryProbeStatus.FAILED);
        assertThat(result.status()).isNotEqualTo(PrimaryProbeStatus.UNREACHABLE);
    }

    private JdbcAuroraTopologyGateway gateway(DataSource dataSource) {
        return new JdbcAuroraTopologyGateway(dataSource, dataSource);
    }
}

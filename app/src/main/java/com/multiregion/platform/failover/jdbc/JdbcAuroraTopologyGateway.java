package com.multiregion.platform.failover.jdbc;

import com.multiregion.platform.failover.domain.PrimaryProbeResult;
import com.multiregion.platform.failover.domain.TopologyInstance;
import com.multiregion.platform.failover.port.AuroraTopologyGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.net.ConnectException;
import java.net.SocketException;
import java.sql.SQLException;
import java.util.List;

@Repository
public class JdbcAuroraTopologyGateway implements AuroraTopologyGateway {

    private static final String CURRENT_WRITER_SQL = """
            SELECT SERVER_ID
            FROM pg_catalog.aurora_replica_status()
            WHERE SESSION_ID = 'MASTER_SESSION_ID'
            """;

    private static final String TOPOLOGY_SQL = """
            SELECT SERVER_ID, SESSION_ID, CPU,
                   COALESCE(REPLICA_LAG_IN_MSEC, 0) AS LAG
            FROM pg_catalog.aurora_replica_status()
            ORDER BY SERVER_ID
            """;

    private final JdbcTemplate primaryProbeJdbcTemplate;
    private final JdbcTemplate runtimeJdbcTemplate;

    public JdbcAuroraTopologyGateway(
            @Qualifier("primaryProbeDataSource") DataSource primaryProbeDataSource,
            @Qualifier("routingDataSource") DataSource routingDataSource) {
        this.primaryProbeJdbcTemplate = new JdbcTemplate(primaryProbeDataSource);
        this.primaryProbeJdbcTemplate.setQueryTimeout(5);
        this.runtimeJdbcTemplate = new JdbcTemplate(routingDataSource);
        this.runtimeJdbcTemplate.setQueryTimeout(5);
    }

    @Override
    public PrimaryProbeResult probePrimary() {
        try {
            String writerId = queryCurrentWriter(primaryProbeJdbcTemplate);
            if (writerId == null || writerId.isBlank()) {
                return PrimaryProbeResult.failed("Primary topology did not identify a writer");
            }
            return PrimaryProbeResult.reachable(writerId);
        } catch (RuntimeException failure) {
            if (isConnectivityFailure(failure)) {
                return PrimaryProbeResult.unreachable(failure.getMessage());
            }
            return PrimaryProbeResult.failed(failure.getMessage());
        }
    }

    @Override
    public boolean isDatabaseConnected() {
        try {
            runtimeJdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    @Override
    public String currentWriter() {
        return queryCurrentWriter(runtimeJdbcTemplate);
    }

    private String queryCurrentWriter(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject(CURRENT_WRITER_SQL, String.class);
    }

    @Override
    public List<TopologyInstance> topology() {
        return runtimeJdbcTemplate.query(TOPOLOGY_SQL, (resultSet, rowNumber) -> new TopologyInstance(
                resultSet.getString("SERVER_ID"),
                "MASTER_SESSION_ID".equals(resultSet.getString("SESSION_ID")),
                resultSet.getObject("CPU"),
                resultSet.getObject("LAG")));
    }

    private boolean isConnectivityFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                String sqlState = sqlException.getSQLState();
                if (sqlState != null && sqlState.startsWith("08")) {
                    return true;
                }
            }
            if (current instanceof ConnectException || current instanceof SocketException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

}

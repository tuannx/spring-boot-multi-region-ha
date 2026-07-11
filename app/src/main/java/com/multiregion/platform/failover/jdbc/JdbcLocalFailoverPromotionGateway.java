package com.multiregion.platform.failover.jdbc;

import com.multiregion.platform.failover.port.FailoverPromotionGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;

@Repository
public class JdbcLocalFailoverPromotionGateway implements FailoverPromotionGateway {

    private final JdbcTemplate jdbcTemplate;

    public JdbcLocalFailoverPromotionGateway(
            @Qualifier("localAdminDataSource") DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.jdbcTemplate.setQueryTimeout(5);
    }

    @Override
    public void enableLocalWriterMode() {
        jdbcTemplate.execute("SELECT pg_catalog.set_writer_mode(true)");
    }

    @Override
    public boolean isLocalWriter() {
        Boolean writer = jdbcTemplate.queryForObject(
                "SELECT pg_catalog.aurora_is_writer()",
                Boolean.class);
        return Boolean.TRUE.equals(writer);
    }

    @Override
    public String localWriterId() {
        return jdbcTemplate.queryForObject(
                "SELECT pg_catalog.aurora_db_instance_identifier()",
                String.class);
    }
}

package com.multiregion.queue;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

@Repository
public class QueueRegionStateRepository {

    private final JdbcTemplate jdbcTemplate;
    private final QueueCoordinationProperties properties;

    public QueueRegionStateRepository(DataSource dataSource, QueueCoordinationProperties properties) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.properties = properties;
    }

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS queue_region_status (
              queue_name varchar(128) NOT NULL,
              region varchar(64) NOT NULL,
              status varchar(16) NOT NULL,
              reason varchar(255),
              updated_at timestamp NOT NULL DEFAULT now(),
              PRIMARY KEY (queue_name, region)
            )
            """);

        for (String queueName : properties.getNames()) {
            for (String region : properties.getRegions()) {
                jdbcTemplate.update("""
                    INSERT INTO queue_region_status (queue_name, region, status, reason, updated_at)
                    VALUES (?, ?, 'UP', 'seeded by app startup', now())
                    ON CONFLICT (queue_name, region) DO NOTHING
                    """, queueName, region);
            }
        }
    }

    public List<QueueRegionState> findAll() {
        return jdbcTemplate.query("""
            SELECT queue_name, region, status, reason, updated_at
            FROM queue_region_status
            ORDER BY queue_name, region
            """, this::mapRow);
    }

    public void markUp(String queueName, String region, String reason) {
        updateStatus(queueName, region, QueueHealthStatus.UP, reason);
    }

    public void markDown(String queueName, String region, String reason) {
        updateStatus(queueName, region, QueueHealthStatus.DOWN, reason);
    }

    private void updateStatus(String queueName, String region, QueueHealthStatus status, String reason) {
        jdbcTemplate.update("""
            INSERT INTO queue_region_status (queue_name, region, status, reason, updated_at)
            VALUES (?, ?, ?, ?, now())
            ON CONFLICT (queue_name, region) DO UPDATE
            SET status = EXCLUDED.status,
                reason = EXCLUDED.reason,
                updated_at = EXCLUDED.updated_at
            """, queueName, region, status.name(), reason);
    }

    private QueueRegionState mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new QueueRegionState(
                rs.getString("queue_name"),
                rs.getString("region"),
                QueueHealthStatus.valueOf(rs.getString("status")),
                rs.getString("reason"),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}

package com.multiregion.controller;

import com.multiregion.model.HealthResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;

/**
 * Health check controller providing region-aware status.
 */
@RestController
public class HealthController {

    @Autowired
    private DataSource dataSource;

    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    @Value("${REGION_ROLE:primary}")
    private String regionRole;

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        boolean dbConnected = isDatabaseConnected();
        String writerNode = getWriterNode();
        String status = dbConnected ? "UP" : "DEGRADED";

        return ResponseEntity.ok(new HealthResponse(
                status,
                awsRegion,
                regionRole,
                writerNode,
                dbConnected,
                "primary".equalsIgnoreCase(regionRole)
        ));
    }

    private boolean isDatabaseConnected() {
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String getWriterNode() {
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            return jdbc.queryForObject(
                    "SELECT SERVER_ID FROM pg_catalog.aurora_replica_status() WHERE SESSION_ID = 'MASTER_SESSION_ID'",
                    String.class
            );
        } catch (Exception e) {
            return "unknown";
        }
    }
}

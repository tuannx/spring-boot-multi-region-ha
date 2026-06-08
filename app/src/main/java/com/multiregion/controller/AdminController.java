package com.multiregion.controller;

import com.multiregion.config.FailoverListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.util.*;

/**
 * Admin controller for failover management and topology inspection.
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    @Autowired
    private DataSource dataSource;

    @Autowired(required = false)
    private FailoverListener failoverListener;

    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    @Value("${REGION_ROLE:primary}")
    private String regionRole;

    /**
     * Force-activate failover for this region.
     */
    @PostMapping("/failover-activate")
    public ResponseEntity<Map<String, Object>> activateFailover() {
        if (failoverListener != null) {
            failoverListener.forceFailover();
        }

        Map<String, Object> response = new HashMap<>();
        response.put("status", "activated");
        response.put("region", awsRegion);
        response.put("role", regionRole);
        response.put("message", "Failover activated for region: " + awsRegion);
        response.put("timestamp", new Date().toString());

        return ResponseEntity.ok(response);
    }

    /**
     * Get current Aurora cluster topology.
     */
    @GetMapping("/topology")
    public ResponseEntity<Map<String, Object>> getTopology() {
        Map<String, Object> response = new HashMap<>();
        response.put("region", awsRegion);
        response.put("role", regionRole);
        response.put("timestamp", new Date().toString());

        List<Map<String, Object>> instances = new ArrayList<>();
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT SERVER_ID, SESSION_ID, CPU, COALESCE(REPLICA_LAG_IN_MSEC, 0) AS LAG " +
                    "FROM pg_catalog.aurora_replica_status() ORDER BY SERVER_ID"
            );
            for (Map<String, Object> row : rows) {
                Map<String, Object> instance = new HashMap<>();
                instance.put("serverId", row.get("SERVER_ID"));
                boolean isWriter = "MASTER_SESSION_ID".equals(row.get("SESSION_ID"));
                instance.put("isWriter", isWriter);
                instance.put("cpu", row.get("CPU"));
                instance.put("lagMs", row.get("LAG"));
                instances.add(instance);
            }
            response.put("instances", instances);
            response.put("dbConnected", true);
        } catch (Exception e) {
            response.put("dbConnected", false);
            response.put("error", e.getMessage());
            response.put("instances", Collections.emptyList());
        }

        return ResponseEntity.ok(response);
    }
}

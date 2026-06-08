package com.multiregion.config;

import com.multiregion.model.HealthResponse;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;

/**
 * Failover listener that monitors region health and manages
 * automatic/manual failover between primary and secondary regions.
 */
@RestController
public class FailoverListener {

    private static final Logger log = LoggerFactory.getLogger(FailoverListener.class);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private MultiRegionConfig multiRegionConfig;

    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    private volatile boolean activated = false;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("========================================");
        log.info("Multi-Region Application Started");
        log.info("Region:          {}", awsRegion);
        log.info("Role:            {} ({})",
                multiRegionConfig.getRegionRole(),
                multiRegionConfig.isPrimary() ? "Writer" : "Reader");
        log.info("Home Region:     {}", multiRegionConfig.getFailoverHomeRegion());
        log.info("Active:          {}", activated ? "YES" : "Standby");
        log.info("========================================");

        if (multiRegionConfig.isPrimary()) {
            activated = true;
            log.info("Primary region activated - accepting read/write traffic");
        } else {
            log.info("Secondary region in standby mode - read-only traffic only");
        }
    }

    /**
     * Scheduled task that checks primary region health.
     * If this is the secondary region and the primary becomes unreachable,
     * automatically activates this region as the new primary.
     */
    @Scheduled(fixedDelay = 15000)
    public void checkPrimaryHealth() {
        if (multiRegionConfig.isPrimary()) {
            // Primary is always active
            return;
        }

        if (activated) {
            // Already activated, skip
            return;
        }

        // Check if primary is reachable via database topology
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            String writerId = jdbc.queryForObject(
                    "SELECT SERVER_ID FROM pg_catalog.aurora_replica_status() WHERE SESSION_ID = 'MASTER_SESSION_ID'",
                    String.class
            );
            // Primary is reachable - stay in standby
            log.debug("Primary instance {} is reachable, staying in standby", writerId);
        } catch (Exception e) {
            log.warn("Primary region appears unreachable: {}", e.getMessage());
            log.warn("Auto-activating this region as failover target!");
            activated = true;
            log.info("*** FAILOVER ACTIVATED - {} is now handling requests ***", awsRegion);
        }
    }

    /**
     * Force-activate failover for this region via admin endpoint.
     */
    @PostMapping("/admin/failover-activate")
    public ResponseEntity<String> forceFailover() {
        activated = true;
        log.warn("*** MANUAL FAILOVER ACTIVATED for {} ***", awsRegion);
        return ResponseEntity.ok("Failover activated for region: " + awsRegion);
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        boolean dbConnected = isDatabaseConnected();

        return ResponseEntity.ok(new HealthResponse(
                dbConnected ? "UP" : "DEGRADED",
                awsRegion,
                multiRegionConfig.getRegionRole(),
                getWriterNode(),
                dbConnected,
                activated
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

    public boolean isActivated() {
        return activated;
    }
}

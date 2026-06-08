package com.multiregion.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Failover listener that monitors region health and manages
 * automatic/manual failover between primary and secondary regions.
 */
@Component
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
            // Toggle local DB from read-only to read-write via trigger control
            try {
                JdbcTemplate jdbc = new JdbcTemplate(dataSource);
                jdbc.execute("SELECT pg_catalog.set_writer_mode(true)");
                log.info("Local database toggled to READ-WRITE mode (triggers enabled)");
            } catch (Exception ex) {
                log.warn("Could not toggle DB mode: {}", ex.getMessage());
            }
            log.info("*** FAILOVER ACTIVATED - {} is now handling requests ***", awsRegion);
        }
    }

    /**
     * Force-activate failover — delegates to AdminController.
     */
    public void forceFailover() {
        log.warn("*** MANUAL FAILOVER ACTIVATED for {} ***", awsRegion);
        activated = true;
        // Toggle local DB from read-only to read-write
        try {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("SELECT pg_catalog.set_writer_mode(true)");
            log.info("Local database toggled to READ-WRITE mode (triggers enabled)");
        } catch (Exception e) {
            log.warn("Could not toggle DB mode: {}", e.getMessage());
        }
    }

    public boolean isActivated() {
        return activated;
    }
}

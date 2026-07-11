package com.multiregion.platform.failover.scheduling;

import com.multiregion.platform.config.MultiRegionConfig;
import com.multiregion.platform.failover.domain.FailoverCheckResult;
import com.multiregion.platform.failover.port.FailoverControl;
import com.multiregion.platform.failover.port.FailoverMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class FailoverListener {

    private static final Logger log = LoggerFactory.getLogger(FailoverListener.class);

    private final FailoverMonitor failoverMonitor;
    private final FailoverControl failoverControl;
    private final MultiRegionConfig multiRegionConfig;

    public FailoverListener(
            FailoverMonitor failoverMonitor,
            FailoverControl failoverControl,
            MultiRegionConfig multiRegionConfig) {
        this.failoverMonitor = failoverMonitor;
        this.failoverControl = failoverControl;
        this.multiRegionConfig = multiRegionConfig;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onApplicationReady() {
        try {
            if (failoverControl.reconcilePersistedState()) {
                log.info("Writer authority reconciled and application write routing is active");
            } else {
                log.warn("Writer authority could not be verified; process remains in standby");
            }
        } catch (RuntimeException reconciliationFailure) {
            log.error(
                    "Could not reconcile persisted failover state; remaining inactive: {}",
                    reconciliationFailure.getMessage(),
                    reconciliationFailure);
        }

        log.info("========================================");
        log.info("Multi-Region Application Started");
        log.info("Region:          {}", multiRegionConfig.awsRegion());
        log.info("Role:            {} ({})",
                multiRegionConfig.regionRole(),
                multiRegionConfig.isPrimary() ? "Writer" : "Reader");
        log.info("Home Region:     {}", multiRegionConfig.failoverHomeRegion());
        log.info("Active:          {}", failoverControl.isActivated() ? "YES" : "Standby");
        log.info("========================================");

        if (failoverControl.isActivated()) {
            log.info("Region is active and routes writes to the authoritative writer");
        } else {
            log.info("Secondary region in standby mode - read-only traffic only");
        }
    }

    @Scheduled(fixedDelay = 15000, scheduler = "failoverTaskScheduler")
    public void checkPrimaryHealth() {
        FailoverCheckResult result = failoverMonitor.checkPrimaryHealth();
        switch (result.status()) {
            case PRIMARY_REGION, ALREADY_ACTIVE -> log.debug(result.detail());
            case PRIMARY_REACHABLE -> log.debug(
                    "Primary instance {} is reachable, staying in standby", result.writerId());
            case PRIMARY_UNREACHABLE -> log.warn(
                    "Primary is unreachable; waiting for promotion threshold: {}",
                    result.detail());
            case PROBE_FAILED -> log.error(
                    "Primary probe failed with a non-connectivity error; refusing auto-promotion: {}",
                    result.detail());
            case FAILOVER_ACTIVATED -> {
                log.warn("Primary region appears unreachable: {}", result.detail());
                log.warn("Auto-activating this region as failover target!");
                log.info("Local database promotion postcondition verified");
                log.info("*** FAILOVER ACTIVATED - {} is now handling requests ***",
                        multiRegionConfig.awsRegion());
            }
            case ACTIVATION_FAILED -> log.error(
                    "Primary health probe failed and failover activation did not complete: {}",
                    result.detail());
        }
    }
}

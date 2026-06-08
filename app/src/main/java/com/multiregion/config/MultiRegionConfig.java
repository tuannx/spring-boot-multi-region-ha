package com.multiregion.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Multi-Region configuration bean.
 * Reads region-specific environment variables and exposes them as beans.
 */
@Configuration
public class MultiRegionConfig {

    private static final Logger log = LoggerFactory.getLogger(MultiRegionConfig.class);

    @Value("${REGION_ROLE:primary}")
    private String regionRole;

    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    @Value("${FAILOVER_HOME_REGION:us-east-1}")
    private String failoverHomeRegion;

    @Value("${ACTIVE_HOME_FAILOVER_MODE:strict-writer}")
    private String activeHomeFailoverMode;

    @Value("${INACTIVE_HOME_FAILOVER_MODE:home-reader-or-writer}")
    private String inactiveHomeFailoverMode;

    @Value("${app.port:8080}")
    private int appPort;

    @Bean
    public String regionRole() {
        log.info("Region role configured: {} (region: {})", regionRole, awsRegion);
        return regionRole;
    }

    @Bean
    public String awsRegion() {
        log.info("AWS Region: {}", awsRegion);
        return awsRegion;
    }

    @Bean
    public String failoverHomeRegion() {
        log.info("Failover home region: {}", failoverHomeRegion);
        return failoverHomeRegion;
    }

    @Bean
    public String activeHomeFailoverMode() {
        return activeHomeFailoverMode;
    }

    @Bean
    public String inactiveHomeFailoverMode() {
        return inactiveHomeFailoverMode;
    }

    /**
     * Returns true if this instance is the primary (writer) region.
     */
    public boolean isPrimary() {
        return "primary".equalsIgnoreCase(regionRole);
    }

    /**
     * Returns true if this instance is the secondary (DR/reader) region.
     */
    public boolean isSecondary() {
        return "secondary".equalsIgnoreCase(regionRole);
    }

    public String getRegionRole() {
        return regionRole;
    }

    public String getAwsRegion() {
        return awsRegion;
    }

    public String getFailoverHomeRegion() {
        return failoverHomeRegion;
    }
}
